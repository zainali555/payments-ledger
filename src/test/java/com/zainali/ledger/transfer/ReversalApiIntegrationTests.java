package com.zainali.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.zainali.ledger.TestcontainersConfiguration;

/** POST /transfers/{id}/reverse against a real Postgres (DECISIONS 10). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ReversalApiIntegrationTests {

	private static final String SIGNED = "CASE e.direction WHEN 'CREDIT' THEN e.amount ELSE -e.amount END";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbc;

	// --- helpers ---

	private static String newId(String prefix) {
		return prefix + "-" + UUID.randomUUID();
	}

	private static RequestPostProcessor user(String subject) {
		return jwt().jwt(jwt -> jwt.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private static RequestPostProcessor admin(String subject) {
		return jwt().jwt(jwt -> jwt.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
	}

	private static RequestPostProcessor admin() {
		return admin(newId("admin"));
	}

	private long createAccount(String owner, String currency) throws Exception {
		String location = mockMvc.perform(post("/accounts").with(user(owner))
				.contentType(MediaType.APPLICATION_JSON).content("{\"currency\":\"" + currency + "\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getHeader("Location");
		return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
	}

	private long deposit(long account, long amount) throws Exception {
		String body = mockMvc.perform(post("/accounts/{id}/deposits", account).with(admin())
				.header("Idempotency-Key", newId("fund")).contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":" + amount + ",\"currency\":\"EUR\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return idOf(body);
	}

	private long fundedAccount(String owner, long funds) throws Exception {
		long account = createAccount(owner, "EUR");
		if (funds > 0) {
			deposit(account, funds);
		}
		return account;
	}

	private long transfer(String owner, long source, long target, long amount) throws Exception {
		String body = mockMvc.perform(post("/transfers").with(user(owner)).header("Idempotency-Key", newId("key"))
				.contentType(MediaType.APPLICATION_JSON).content("{\"sourceAccountId\":" + source + ",\"targetAccountId\":"
						+ target + ",\"amount\":" + amount + ",\"currency\":\"EUR\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return idOf(body);
	}

	private MockHttpServletRequestBuilder reverseRequest(RequestPostProcessor caller, String key, long transferId) {
		MockHttpServletRequestBuilder request = post("/transfers/{id}/reverse", transferId).with(caller);
		return key == null ? request : request.header("Idempotency-Key", key);
	}

	private ResultActions reverse(RequestPostProcessor caller, String key, long transferId) throws Exception {
		return mockMvc.perform(reverseRequest(caller, key, transferId));
	}

	private ResultActions reverse(long transferId) throws Exception {
		return reverse(admin(), newId("rev"), transferId);
	}

	private long balance(long accountId) {
		return jdbc.sql("SELECT balance FROM accounts WHERE id = :id").param("id", accountId).query(Long.class).single();
	}

	private String statusOf(long transferId) {
		return jdbc.sql("SELECT status FROM transfers WHERE id = :id").param("id", transferId).query(String.class).single();
	}

	private long reversalsOf(long transferId) {
		return jdbc.sql("SELECT count(*) FROM transfers WHERE reverses_transfer_id = :id").param("id", transferId)
				.query(Long.class).single();
	}

	private long entrySum(long accountId) {
		return jdbc.sql("SELECT COALESCE(SUM(" + SIGNED + "), 0) FROM ledger_entries e WHERE e.account_id = :id")
				.param("id", accountId).query(Long.class).single();
	}

	private long keyRows(String owner, String key) {
		return jdbc.sql("SELECT count(*) FROM idempotency_keys WHERE owner_id = :o AND key = :k").param("o", owner)
				.param("k", key).query(Long.class).single();
	}

	private static long idOf(String json) {
		Matcher matcher = Pattern.compile("\"id\":(\\d+)").matcher(json);
		assertThat(matcher.find()).isTrue();
		return Long.parseLong(matcher.group(1));
	}

	private record Outcome(int status, String body, String replayed) {
	}

	private List<Outcome> runConcurrently(List<MockHttpServletRequestBuilder> requests) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(requests.size());
		CountDownLatch ready = new CountDownLatch(requests.size());
		CountDownLatch go = new CountDownLatch(1);
		try {
			List<Future<Outcome>> futures = new ArrayList<>();
			for (MockHttpServletRequestBuilder request : requests) {
				Callable<Outcome> task = () -> {
					ready.countDown();
					go.await();
					MvcResult result = mockMvc.perform(request).andReturn();
					return new Outcome(result.getResponse().getStatus(), result.getResponse().getContentAsString(),
							result.getResponse().getHeader("Idempotency-Replayed"));
				};
				futures.add(pool.submit(task));
			}
			ready.await();
			go.countDown();
			List<Outcome> outcomes = new ArrayList<>();
			for (Future<Outcome> future : futures) {
				outcomes.add(future.get());
			}
			return outcomes;
		}
		finally {
			pool.shutdownNow();
		}
	}

	// --- success ---

	@Test
	void reversalMovesTheMoneyBackAndMarksTheOriginalReversed() throws Exception {
		String alice = newId("alice");
		String bob = newId("bob");
		long aliceAccount = fundedAccount(alice, 1_000);
		long bobAccount = fundedAccount(bob, 50);
		long original = transfer(alice, aliceAccount, bobAccount, 400);

		MvcResult result = reverse(original)
				.andExpect(status().isCreated())
				.andExpect(header().doesNotExist("Idempotency-Replayed"))
				.andExpect(jsonPath("$.type", is("REVERSAL")))
				.andExpect(jsonPath("$.status", is("COMPLETED")))
				.andExpect(jsonPath("$.sourceAccountId", is((int) bobAccount)))
				.andExpect(jsonPath("$.targetAccountId", is((int) aliceAccount)))
				.andExpect(jsonPath("$.amount", is(400)))
				.andExpect(jsonPath("$.currency", is("EUR")))
				.andExpect(jsonPath("$.reversesTransferId", is((int) original)))
				.andReturn();

		long reversal = idOf(result.getResponse().getContentAsString());
		assertThat(result.getResponse().getHeader("Location")).endsWith("/transfers/" + reversal);
		assertThat(balance(aliceAccount)).isEqualTo(1_000);
		assertThat(balance(bobAccount)).isEqualTo(50);
		assertThat(entrySum(aliceAccount)).isEqualTo(1_000);
		assertThat(entrySum(bobAccount)).isEqualTo(50);

		// The original is still readable by its parties and now says REVERSED; the reversal is readable too.
		mockMvc.perform(get("/transfers/{id}", original).with(user(alice)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status", is("REVERSED")));
		mockMvc.perform(get("/transfers/{id}", reversal).with(user(bob)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.type", is("REVERSAL")));
	}

	@Test
	void reversalAppendsTwoEntriesAndLeavesTheOriginalEntriesUntouched() throws Exception {
		String alice = newId("alice");
		long aliceAccount = fundedAccount(alice, 1_000);
		long bobAccount = createAccount(newId("bob"), "EUR");
		long original = transfer(alice, aliceAccount, bobAccount, 300);
		String originalEntriesBefore = jdbc.sql("SELECT string_agg(id || ':' || account_id || ':' || direction || ':' "
				+ "|| amount || ':' || created_at, ',' ORDER BY id) FROM ledger_entries WHERE transfer_id = :t")
				.param("t", original).query(String.class).single();

		long reversal = idOf(reverse(original).andExpect(status().isCreated()).andReturn().getResponse()
				.getContentAsString());

		String originalEntriesAfter = jdbc.sql("SELECT string_agg(id || ':' || account_id || ':' || direction || ':' "
				+ "|| amount || ':' || created_at, ',' ORDER BY id) FROM ledger_entries WHERE transfer_id = :t")
				.param("t", original).query(String.class).single();
		assertThat(originalEntriesAfter).isEqualTo(originalEntriesBefore);

		List<String> reversalEntries = jdbc.sql("SELECT account_id || ':' || direction || ':' || amount "
				+ "FROM ledger_entries WHERE transfer_id = :t ORDER BY direction").param("t", reversal)
				.query(String.class).list();
		assertThat(reversalEntries).containsExactly(aliceAccount + ":CREDIT:300", bobAccount + ":DEBIT:300");
	}

	// --- once only ---

	@Test
	void secondReversalIsAlreadyReversedAndMovesNothing() throws Exception {
		String alice = newId("alice");
		long aliceAccount = fundedAccount(alice, 1_000);
		long bobAccount = fundedAccount(newId("bob"), 500);
		long original = transfer(alice, aliceAccount, bobAccount, 200);
		reverse(original).andExpect(status().isCreated());

		reverse(original)
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code", is("ALREADY_REVERSED")));

		assertThat(reversalsOf(original)).isEqualTo(1);
		assertThat(balance(aliceAccount)).isEqualTo(1_000);
		assertThat(balance(bobAccount)).isEqualTo(500);
	}

	@Test
	void concurrentReversalsWithDifferentKeysSucceedExactlyOnce() throws Exception {
		String alice = newId("alice");
		long aliceAccount = fundedAccount(alice, 1_000);
		long bobAccount = fundedAccount(newId("bob"), 5_000);
		long original = transfer(alice, aliceAccount, bobAccount, 700);

		List<MockHttpServletRequestBuilder> requests = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			requests.add(reverseRequest(admin(), newId("rev"), original));
		}
		List<Outcome> outcomes = runConcurrently(requests);

		assertThat(outcomes).filteredOn(o -> o.status() == 201).hasSize(1);
		assertThat(outcomes).filteredOn(o -> o.status() != 201).hasSize(7)
				.allSatisfy(o -> {
					assertThat(o.status()).isEqualTo(422);
					assertThat(o.body()).contains("\"code\":\"ALREADY_REVERSED\"");
				});
		assertThat(reversalsOf(original)).isEqualTo(1);
		assertThat(statusOf(original)).isEqualTo("REVERSED");
		assertThat(balance(aliceAccount)).isEqualTo(1_000).isEqualTo(entrySum(aliceAccount));
		assertThat(balance(bobAccount)).isEqualTo(5_000).isEqualTo(entrySum(bobAccount));
	}

	// --- not reversible ---

	@Test
	void reversingAReversalIsNotReversible() throws Exception {
		String alice = newId("alice");
		long aliceAccount = fundedAccount(alice, 1_000);
		long bobAccount = fundedAccount(newId("bob"), 500);
		long original = transfer(alice, aliceAccount, bobAccount, 200);
		long reversal = idOf(reverse(original).andExpect(status().isCreated()).andReturn().getResponse()
				.getContentAsString());

		reverse(reversal)
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code", is("NOT_REVERSIBLE")));

		assertThat(reversalsOf(reversal)).isZero();
		assertThat(statusOf(reversal)).isEqualTo("COMPLETED");
		assertThat(balance(aliceAccount)).isEqualTo(1_000);
		assertThat(balance(bobAccount)).isEqualTo(500);
	}

	@Test
	void reversingADepositIsNotReversible() throws Exception {
		long account = createAccount(newId("alice"), "EUR");
		long deposit = deposit(account, 900);

		reverse(deposit)
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code", is("NOT_REVERSIBLE")));

		assertThat(reversalsOf(deposit)).isZero();
		assertThat(statusOf(deposit)).isEqualTo("COMPLETED");
		assertThat(balance(account)).isEqualTo(900);
	}

	@Test
	void missingTransferIsNotFound() throws Exception {
		reverse(Long.MAX_VALUE)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code", is("NOT_FOUND")));
	}

	// --- insufficient funds ---

	@Test
	void recipientWhoSpentTheMoneyIsInsufficientFundsAndNothingIsStored() throws Exception {
		String alice = newId("alice");
		String bob = newId("bob");
		long aliceAccount = fundedAccount(alice, 1_000);
		long bobAccount = createAccount(bob, "EUR");
		long carolAccount = createAccount(newId("carol"), "EUR");
		long original = transfer(alice, aliceAccount, bobAccount, 400);
		transfer(bob, bobAccount, carolAccount, 250);
		String adminSubject = newId("admin");
		String key = newId("rev");

		reverse(admin(adminSubject), key, original)
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code", is("INSUFFICIENT_FUNDS")));

		assertThat(statusOf(original)).isEqualTo("COMPLETED");
		assertThat(reversalsOf(original)).isZero();
		assertThat(balance(aliceAccount)).isEqualTo(600);
		assertThat(balance(bobAccount)).isEqualTo(150);
		assertThat(keyRows(adminSubject, key)).as("failures are not stored against the key").isZero();

		// Once the recipient has the funds again, a retry with the same key runs and succeeds.
		deposit(bobAccount, 250);
		reverse(admin(adminSubject), key, original)
				.andExpect(status().isCreated())
				.andExpect(header().doesNotExist("Idempotency-Replayed"));
		assertThat(balance(aliceAccount)).isEqualTo(1_000);
		assertThat(balance(bobAccount)).isZero();
	}

	// --- idempotency ---

	@Test
	void repeatedKeyReplaysTheOriginalResponseAndReversesOnce() throws Exception {
		String alice = newId("alice");
		long aliceAccount = fundedAccount(alice, 1_000);
		long bobAccount = createAccount(newId("bob"), "EUR");
		long original = transfer(alice, aliceAccount, bobAccount, 300);
		String adminSubject = newId("admin");
		String key = newId("rev");

		MvcResult first = reverse(admin(adminSubject), key, original).andExpect(status().isCreated()).andReturn();
		MvcResult replay = reverse(admin(adminSubject), key, original)
				.andExpect(status().isCreated())
				.andExpect(header().string("Idempotency-Replayed", "true"))
				.andReturn();

		assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
		assertThat(replay.getResponse().getHeader("Location")).isEqualTo(first.getResponse().getHeader("Location"));
		assertThat(reversalsOf(original)).isEqualTo(1);
		assertThat(balance(aliceAccount)).isEqualTo(1_000);
		assertThat(balance(bobAccount)).isZero();
	}

	@Test
	void concurrentRequestsWithTheSameKeyReverseOnceAndReplayTheRest() throws Exception {
		String alice = newId("alice");
		long aliceAccount = fundedAccount(alice, 1_000);
		long bobAccount = createAccount(newId("bob"), "EUR");
		long original = transfer(alice, aliceAccount, bobAccount, 300);
		String adminSubject = newId("admin");
		String key = newId("rev");

		List<MockHttpServletRequestBuilder> requests = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			requests.add(reverseRequest(admin(adminSubject), key, original));
		}
		List<Outcome> outcomes = runConcurrently(requests);

		assertThat(outcomes).allSatisfy(o -> assertThat(o.status()).isEqualTo(201));
		assertThat(outcomes).filteredOn(o -> o.replayed() == null).hasSize(1);
		assertThat(outcomes).extracting(Outcome::body).containsOnly(outcomes.get(0).body());
		assertThat(reversalsOf(original)).isEqualTo(1);
		assertThat(balance(aliceAccount)).isEqualTo(1_000);
	}

	@Test
	void sameKeyForAnotherTransferIsKeyReused() throws Exception {
		String alice = newId("alice");
		long aliceAccount = fundedAccount(alice, 1_000);
		long bobAccount = createAccount(newId("bob"), "EUR");
		long first = transfer(alice, aliceAccount, bobAccount, 100);
		long second = transfer(alice, aliceAccount, bobAccount, 100);
		String adminSubject = newId("admin");
		String key = newId("rev");
		reverse(admin(adminSubject), key, first).andExpect(status().isCreated());

		reverse(admin(adminSubject), key, second)
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code", is("IDEMPOTENCY_KEY_REUSED")));
		assertThat(statusOf(second)).isEqualTo("COMPLETED");
	}

	@Test
	void missingKeyIsRequired() throws Exception {
		reverse(admin(), null, 1)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code", is("IDEMPOTENCY_KEY_REQUIRED")));
	}

	// --- security ---

	@Test
	void userCannotReverseEvenTheirOwnTransfer() throws Exception {
		String alice = newId("alice");
		long aliceAccount = fundedAccount(alice, 1_000);
		long bobAccount = createAccount(newId("bob"), "EUR");
		long original = transfer(alice, aliceAccount, bobAccount, 100);

		reverse(user(alice), newId("rev"), original).andExpect(status().isForbidden());
		assertThat(statusOf(original)).isEqualTo("COMPLETED");
	}

	@Test
	void anonymousIsUnauthorized() throws Exception {
		mockMvc.perform(post("/transfers/{id}/reverse", 1).header("Idempotency-Key", newId("rev")))
				.andExpect(status().isUnauthorized());
	}

	// --- invariants ---

	@Test
	void ledgerInvariantsHoldAfterReversals() throws Exception {
		String alice = newId("alice");
		String bob = newId("bob");
		long aliceAccount = fundedAccount(alice, 2_000);
		long bobAccount = fundedAccount(bob, 100);
		long first = transfer(alice, aliceAccount, bobAccount, 500);
		long second = transfer(bob, bobAccount, aliceAccount, 600);
		long third = transfer(alice, aliceAccount, bobAccount, 50);
		// alice 2050, bob 50. Reverse second first, so bob can afford to pay back first.
		reverse(second).andExpect(status().isCreated());
		reverse(first).andExpect(status().isCreated());
		reverse(first).andExpect(status().isUnprocessableContent());
		transfer(bob, bobAccount, aliceAccount, 140);
		reverse(third).andExpect(status().isUnprocessableContent()); // bob holds 10, needs 50

		assertThat(balance(aliceAccount)).isEqualTo(2_000 - 50 + 140).isEqualTo(entrySum(aliceAccount));
		assertThat(balance(bobAccount)).isEqualTo(100 + 50 - 140).isEqualTo(entrySum(bobAccount));

		List<Long> unbalanced = jdbc.sql("SELECT t.id FROM transfers t LEFT JOIN ledger_entries e ON e.transfer_id = t.id "
				+ "GROUP BY t.id HAVING COALESCE(SUM(" + SIGNED + "), 0) <> 0 OR COUNT(e.id) <> 2").query(Long.class).list();
		assertThat(unbalanced).as("transfers whose signed entries do not sum to zero").isEmpty();

		List<Long> drifting = jdbc.sql("SELECT a.id FROM accounts a LEFT JOIN ledger_entries e ON e.account_id = a.id "
				+ "GROUP BY a.id, a.balance HAVING a.balance <> COALESCE(SUM(" + SIGNED + "), 0)").query(Long.class).list();
		assertThat(drifting).as("accounts whose cached balance differs from their entry sum").isEmpty();

		// Every reversal mirrors its original: accounts swapped, same amount and currency, and the original is REVERSED.
		List<Long> badReversals = jdbc.sql("SELECT r.id FROM transfers r JOIN transfers o ON o.id = r.reverses_transfer_id "
				+ "WHERE r.type = 'REVERSAL' AND (r.source_account_id <> o.target_account_id "
				+ "OR r.target_account_id <> o.source_account_id OR r.amount <> o.amount OR r.currency <> o.currency "
				+ "OR o.type <> 'TRANSFER' OR o.status <> 'REVERSED')").query(Long.class).list();
		assertThat(badReversals).as("reversals that do not mirror a REVERSED transfer").isEmpty();

		List<Long> orphanReversed = jdbc.sql("SELECT o.id FROM transfers o WHERE o.status = 'REVERSED' "
				+ "AND NOT EXISTS (SELECT 1 FROM transfers r WHERE r.reverses_transfer_id = o.id)").query(Long.class).list();
		assertThat(orphanReversed).as("REVERSED transfers without a reversal").isEmpty();
	}

}
