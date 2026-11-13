package com.zainali.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TransferApiIntegrationTests {

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

	private static String body(long source, long target, long amount, String currency) {
		return "{\"sourceAccountId\":" + source + ",\"targetAccountId\":" + target + ",\"amount\":" + amount
				+ ",\"currency\":\"" + currency + "\"}";
	}

	private long createAccount(String owner, String currency) throws Exception {
		String location = mockMvc.perform(post("/accounts").with(user(owner))
				.contentType(MediaType.APPLICATION_JSON).content("{\"currency\":\"" + currency + "\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getHeader("Location");
		return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
	}

	/** An account owned by {@code owner} holding {@code funds}. */
	private long fundedAccount(String owner, String currency, long funds) throws Exception {
		long account = createAccount(owner, currency);
		if (funds > 0) {
			mockMvc.perform(post("/accounts/{id}/deposits", account).with(admin()).header("Idempotency-Key", newId("fund"))
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"amount\":" + funds + ",\"currency\":\"" + currency + "\"}"))
					.andExpect(status().isCreated());
		}
		return account;
	}

	private MockHttpServletRequestBuilder transferRequest(RequestPostProcessor caller, String key, String body) {
		MockHttpServletRequestBuilder request = post("/transfers").with(caller).contentType(MediaType.APPLICATION_JSON)
				.content(body);
		return key == null ? request : request.header("Idempotency-Key", key);
	}

	private ResultActions transfer(RequestPostProcessor caller, String key, String body) throws Exception {
		return mockMvc.perform(transferRequest(caller, key, body));
	}

	private long balance(long accountId) {
		return jdbc.sql("SELECT balance FROM accounts WHERE id = :id").param("id", accountId).query(Long.class).single();
	}

	private long externalBalance(String currency) {
		return jdbc.sql("SELECT balance FROM accounts WHERE type = 'EXTERNAL' AND currency = :c").param("c", currency)
				.query(Long.class).single();
	}

	private long externalId(String currency) {
		return jdbc.sql("SELECT id FROM accounts WHERE type = 'EXTERNAL' AND currency = :c").param("c", currency)
				.query(Long.class).single();
	}

	private long transfersFrom(long accountId) {
		return jdbc.sql("SELECT count(*) FROM transfers WHERE source_account_id = :id AND type = 'TRANSFER'")
				.param("id", accountId).query(Long.class).single();
	}

	private long keyRows(String owner, String key) {
		return jdbc.sql("SELECT count(*) FROM idempotency_keys WHERE owner_id = :o AND key = :k").param("o", owner)
				.param("k", key).query(Long.class).single();
	}

	private static long idOf(String json) {
		var matcher = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(json);
		assertThat(matcher.find()).isTrue();
		return Long.parseLong(matcher.group(1));
	}

	// --- success ---

	@Test
	void ownerTransferMovesMoneyAndReturns201WithALocationHeader() throws Exception {
		String alice = newId("alice");
		long source = fundedAccount(alice, "EUR", 1_000);
		long target = fundedAccount(newId("bob"), "EUR", 50);

		MvcResult result = transfer(user(alice), newId("key"), body(source, target, 400, "EUR"))
				.andExpect(status().isCreated())
				.andExpect(header().doesNotExist("Idempotency-Replayed"))
				.andExpect(jsonPath("$.id", notNullValue()))
				.andExpect(jsonPath("$.type", is("TRANSFER")))
				.andExpect(jsonPath("$.status", is("COMPLETED")))
				.andExpect(jsonPath("$.sourceAccountId", is((int) source)))
				.andExpect(jsonPath("$.targetAccountId", is((int) target)))
				.andExpect(jsonPath("$.amount", is(400)))
				.andExpect(jsonPath("$.currency", is("EUR")))
				.andExpect(jsonPath("$.createdAt", notNullValue()))
				.andReturn();

		long id = idOf(result.getResponse().getContentAsString());
		assertThat(result.getResponse().getHeader("Location")).endsWith("/transfers/" + id);
		assertThat(balance(source)).isEqualTo(600);
		assertThat(balance(target)).isEqualTo(450);
		mockMvc.perform(get(result.getResponse().getHeader("Location")).with(user(alice)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id", is((int) id)));
	}

	@Test
	void transferWritesOneDebitAndOneCreditThatSumToZero() throws Exception {
		String alice = newId("alice");
		long source = fundedAccount(alice, "EUR", 1_000);
		long target = createAccount(newId("bob"), "EUR");

		transfer(user(alice), newId("key"), body(source, target, 300, "EUR")).andExpect(status().isCreated());

		long transferId = jdbc.sql("SELECT id FROM transfers WHERE source_account_id = :id").param("id", source)
				.query(Long.class).single();
		var entries = jdbc.sql("SELECT account_id, direction, amount FROM ledger_entries WHERE transfer_id = :t")
				.param("t", transferId).query().listOfRows();
		assertThat(entries).hasSize(2);
		assertThat(entries).anySatisfy(e -> {
			assertThat(e.get("direction")).isEqualTo("DEBIT");
			assertThat(e.get("account_id")).isEqualTo(source);
			assertThat(e.get("amount")).isEqualTo(300L);
		});
		assertThat(entries).anySatisfy(e -> {
			assertThat(e.get("direction")).isEqualTo("CREDIT");
			assertThat(e.get("account_id")).isEqualTo(target);
			assertThat(e.get("amount")).isEqualTo(300L);
		});
	}

	@Test
	void transferOfTheWholeBalanceLeavesZero() throws Exception {
		String alice = newId("alice");
		long source = fundedAccount(alice, "EUR", 500);
		long target = createAccount(newId("bob"), "EUR");

		transfer(user(alice), newId("key"), body(source, target, 500, "EUR")).andExpect(status().isCreated());

		assertThat(balance(source)).isZero();
		assertThat(balance(target)).isEqualTo(500);
	}

	@Test
	void transferDoesNotTouchTheExternalAccount() throws Exception {
		String alice = newId("alice");
		long source = fundedAccount(alice, "EUR", 500);
		long target = createAccount(newId("bob"), "EUR");
		long externalBefore = externalBalance("EUR");

		transfer(user(alice), newId("key"), body(source, target, 200, "EUR")).andExpect(status().isCreated());

		assertThat(externalBalance("EUR")).isEqualTo(externalBefore);
	}

	@Test
	void adminCanTransferBetweenAccountsTheyDoNotOwn() throws Exception {
		long source = fundedAccount(newId("alice"), "USD", 900);
		long target = createAccount(newId("bob"), "USD");

		transfer(admin(), newId("key"), body(source, target, 900, "USD")).andExpect(status().isCreated());

		assertThat(balance(source)).isZero();
		assertThat(balance(target)).isEqualTo(900);
	}

	// --- security ---

	@Test
	void transferWithoutTokenReturns401() throws Exception {
		mockMvc.perform(post("/transfers").header("Idempotency-Key", "k").contentType(MediaType.APPLICATION_JSON)
				.content(body(1, 2, 100, "EUR")))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void tokenWithoutAnyRoleCannotTransferOrRead() throws Exception {
		transfer(jwt().jwt(jwt -> jwt.subject(newId("norole"))), newId("key"), body(1, 2, 100, "EUR"))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/transfers/1").with(jwt().jwt(jwt -> jwt.subject(newId("norole")))))
				.andExpect(status().isForbidden());
	}

	@Test
	void sendingFromSomeoneElsesAccountReturns404AndMovesNothing() throws Exception {
		long victim = fundedAccount(newId("alice"), "EUR", 1_000);
		long mine = createAccount(newId("mallory"), "EUR");
		String mallory = newId("mallory");

		transfer(user(mallory), newId("key"), body(victim, mine, 500, "EUR"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code", is("NOT_FOUND")));

		assertThat(balance(victim)).isEqualTo(1_000);
		assertThat(balance(mine)).isZero();
	}

	@Test
	void sendingFromSomeoneElsesAccountLooksTheSameAsSendingFromAMissingOne() throws Exception {
		long victim = fundedAccount(newId("alice"), "EUR", 1_000);
		long mine = createAccount(newId("mallory"), "EUR");
		RequestPostProcessor mallory = user(newId("mallory"));

		String notMine = transfer(mallory, newId("key"), body(victim, mine, 5, "EUR")).andReturn().getResponse()
				.getContentAsString();
		String missing = transfer(mallory, newId("key"), body(Long.MAX_VALUE, mine, 5, "EUR")).andReturn().getResponse()
				.getContentAsString();

		assertThat(notMine).isEqualTo(missing);
	}

	@Test
	void userCannotSendFromAnExternalAccount() throws Exception {
		long mine = createAccount(newId("mallory"), "EUR");
		long externalBefore = externalBalance("EUR");

		transfer(user(newId("mallory")), newId("key"), body(externalId("EUR"), mine, 500, "EUR"))
				.andExpect(status().isNotFound());

		assertThat(externalBalance("EUR")).isEqualTo(externalBefore);
		assertThat(balance(mine)).isZero();
	}

	// --- GET /transfers/{id} ---

	private long makeTransfer(String senderOwner, String receiverOwner) throws Exception {
		long source = fundedAccount(senderOwner, "EUR", 100);
		long target = createAccount(receiverOwner, "EUR");
		String response = transfer(user(senderOwner), newId("key"), body(source, target, 40, "EUR"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return idOf(response);
	}

	@Test
	void bothPartiesAndAnAdminCanReadATransfer() throws Exception {
		String alice = newId("alice");
		String bob = newId("bob");
		long id = makeTransfer(alice, bob);

		for (RequestPostProcessor caller : List.of(user(alice), user(bob), admin())) {
			mockMvc.perform(get("/transfers/{id}", id).with(caller))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.id", is((int) id)))
					.andExpect(jsonPath("$.type", is("TRANSFER")))
					.andExpect(jsonPath("$.amount", is(40)));
		}
	}

	@Test
	void aStrangerGetsTheSame404ForAnExistingAndAMissingTransfer() throws Exception {
		long id = makeTransfer(newId("alice"), newId("bob"));
		RequestPostProcessor stranger = user(newId("mallory"));

		String existing = mockMvc.perform(get("/transfers/{id}", id).with(stranger)).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code", is("NOT_FOUND"))).andReturn().getResponse().getContentAsString();
		String missing = mockMvc.perform(get("/transfers/{id}", Long.MAX_VALUE).with(stranger))
				.andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();

		// The problem body echoes the request path in "instance"; everything else must be identical.
		assertThat(existing.replaceAll("\"instance\":\"[^\"]*\"", "")).isEqualTo(missing.replaceAll("\"instance\":\"[^\"]*\"", ""));
	}

	@Test
	void theRecipientOfADepositCanReadIt() throws Exception {
		String alice = newId("alice");
		long account = fundedAccount(alice, "EUR", 10);
		long depositId = jdbc.sql("SELECT id FROM transfers WHERE target_account_id = :id").param("id", account)
				.query(Long.class).single();

		mockMvc.perform(get("/transfers/{id}", depositId).with(user(alice)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.type", is("DEPOSIT")));
	}

	@Test
	void nonNumericTransferIdIsRejected() throws Exception {
		mockMvc.perform(get("/transfers/abc").with(admin()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
	}

	@Test
	void readingATransferWithoutTokenReturns401() throws Exception {
		mockMvc.perform(get("/transfers/1")).andExpect(status().isUnauthorized());
	}

	// --- business rules ---

	@Test
	void sameAccountIsRejected() throws Exception {
		String alice = newId("alice");
		long account = fundedAccount(alice, "EUR", 100);

		transfer(user(alice), newId("key"), body(account, account, 10, "EUR"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code", is("SAME_ACCOUNT")));
		transfer(admin(), newId("key"), body(account, account, 10, "EUR"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code", is("SAME_ACCOUNT")));

		assertThat(balance(account)).isEqualTo(100);
	}

	@Test
	void sameAccountOnSomeoneElsesAccountIsNotFound() throws Exception {
		long account = fundedAccount(newId("alice"), "EUR", 100);

		transfer(user(newId("mallory")), newId("key"), body(account, account, 10, "EUR"))
				.andExpect(status().isNotFound());
	}

	@Test
	void sendingToAnExternalAccountIsRejected() throws Exception {
		String alice = newId("alice");
		long source = fundedAccount(alice, "EUR", 100);
		long externalBefore = externalBalance("EUR");

		transfer(user(alice), newId("key"), body(source, externalId("EUR"), 10, "EUR"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code", is("EXTERNAL_ACCOUNT_NOT_ALLOWED")));

		assertThat(balance(source)).isEqualTo(100);
		assertThat(externalBalance("EUR")).isEqualTo(externalBefore);
	}

	@Test
	void adminCannotUseAnExternalAccountAsSourceOrTarget() throws Exception {
		long account = fundedAccount(newId("alice"), "EUR", 100);

		transfer(admin(), newId("key"), body(externalId("EUR"), account, 10, "EUR"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code", is("EXTERNAL_ACCOUNT_NOT_ALLOWED")));
		transfer(admin(), newId("key"), body(account, externalId("EUR"), 10, "EUR"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code", is("EXTERNAL_ACCOUNT_NOT_ALLOWED")));

		assertThat(balance(account)).isEqualTo(100);
	}

	@Test
	void currencyMismatchBetweenAccountsIsRejected() throws Exception {
		String alice = newId("alice");
		long eur = fundedAccount(alice, "EUR", 100);
		long usd = createAccount(newId("bob"), "USD");

		transfer(user(alice), newId("key"), body(eur, usd, 10, "EUR"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code", is("CURRENCY_MISMATCH")));

		assertThat(balance(eur)).isEqualTo(100);
		assertThat(balance(usd)).isZero();
	}

	@Test
	void requestCurrencyThatDoesNotMatchTheAccountsIsRejected() throws Exception {
		String alice = newId("alice");
		long source = fundedAccount(alice, "EUR", 100);
		long target = createAccount(newId("bob"), "EUR");

		transfer(user(alice), newId("key"), body(source, target, 10, "USD"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code", is("CURRENCY_MISMATCH")));

		assertThat(balance(source)).isEqualTo(100);
	}

	@Test
	void insufficientFundsIsRejectedAndLeavesNothing() throws Exception {
		String alice = newId("alice");
		String key = newId("key");
		long source = fundedAccount(alice, "EUR", 100);
		long target = createAccount(newId("bob"), "EUR");

		transfer(user(alice), key, body(source, target, 101, "EUR"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code", is("INSUFFICIENT_FUNDS")));

		assertThat(balance(source)).isEqualTo(100);
		assertThat(balance(target)).isZero();
		assertThat(transfersFrom(source)).isZero();
		assertThat(keyRows(alice, key)).isZero();
	}

	@Test
	void sendingToAMissingAccountReturns404() throws Exception {
		String alice = newId("alice");
		long source = fundedAccount(alice, "EUR", 100);

		transfer(user(alice), newId("key"), body(source, Long.MAX_VALUE, 10, "EUR"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code", is("NOT_FOUND")));

		assertThat(balance(source)).isEqualTo(100);
	}

	// --- validation and the Idempotency-Key header ---

	@Test
	void missingKeyIsRejectedBeforeTheBodyIsRead() throws Exception {
		transfer(admin(), null, "{not json")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code", is("IDEMPOTENCY_KEY_REQUIRED")));
	}

	@Test
	void blankAndOverlongKeysAreRejected() throws Exception {
		String alice = newId("alice");
		long source = fundedAccount(alice, "EUR", 100);
		long target = createAccount(newId("bob"), "EUR");

		transfer(user(alice), "  ", body(source, target, 10, "EUR"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code", is("IDEMPOTENCY_KEY_REQUIRED")));
		transfer(user(alice), "k".repeat(256), body(source, target, 10, "EUR"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));

		assertThat(balance(source)).isEqualTo(100);
	}

	@Test
	void invalidBodiesAreRejectedWithValidationFailed() throws Exception {
		String alice = newId("alice");
		long source = fundedAccount(alice, "EUR", 100);
		long target = createAccount(newId("bob"), "EUR");
		List<String> bodies = List.of(
				body(source, target, 0, "EUR"),
				body(source, target, -5, "EUR"),
				body(source, target, 1_000_000_000_001L, "EUR"),
				"{\"targetAccountId\":" + target + ",\"amount\":10,\"currency\":\"EUR\"}",
				"{\"sourceAccountId\":" + source + ",\"amount\":10,\"currency\":\"EUR\"}",
				"{\"sourceAccountId\":" + source + ",\"targetAccountId\":" + target + ",\"currency\":\"EUR\"}",
				"{\"sourceAccountId\":" + source + ",\"targetAccountId\":" + target + ",\"amount\":10}",
				"{\"sourceAccountId\":" + source + ",\"targetAccountId\":" + target + ",\"amount\":10,\"currency\":\"GBP\"}",
				"{\"sourceAccountId\":" + source + ",\"targetAccountId\":" + target + ",\"amount\":10.5,\"currency\":\"EUR\"}",
				"{\"sourceAccountId\":\"abc\",\"targetAccountId\":" + target + ",\"amount\":10,\"currency\":\"EUR\"}",
				"{}",
				"{not json");

		for (String body : bodies) {
			transfer(user(alice), newId("key"), body)
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
		}

		assertThat(balance(source)).isEqualTo(100);
		assertThat(transfersFrom(source)).isZero();
	}

	// --- duplicates: sequential ---

	@Test
	void duplicateRequestReplaysTheOriginalResponseAndMovesMoneyOnce() throws Exception {
		String alice = newId("alice");
		String key = newId("key");
		long source = fundedAccount(alice, "EUR", 1_000);
		long target = createAccount(newId("bob"), "EUR");

		MvcResult first = transfer(user(alice), key, body(source, target, 300, "EUR"))
				.andExpect(status().isCreated())
				.andExpect(header().doesNotExist("Idempotency-Replayed"))
				.andReturn();
		MvcResult second = transfer(user(alice), key, body(source, target, 300, "EUR"))
				.andExpect(status().isCreated())
				.andExpect(header().string("Idempotency-Replayed", "true"))
				.andExpect(header().string("Location", first.getResponse().getHeader("Location")))
				.andReturn();

		assertThat(second.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
		assertThat(transfersFrom(source)).isEqualTo(1);
		assertThat(balance(source)).isEqualTo(700);
		assertThat(balance(target)).isEqualTo(300);
		assertThat(jdbc.sql("SELECT count(*) FROM ledger_entries e JOIN transfers t ON t.id = e.transfer_id "
				+ "WHERE t.source_account_id = :id AND t.type = 'TRANSFER'").param("id", source).query(Long.class).single())
				.isEqualTo(2);
	}

	@Test
	void sameKeyWithADifferentRequestIsRejectedAsReused() throws Exception {
		String alice = newId("alice");
		String key = newId("key");
		long source = fundedAccount(alice, "EUR", 1_000);
		long target = createAccount(newId("bob"), "EUR");
		long otherTarget = createAccount(newId("carol"), "EUR");

		transfer(user(alice), key, body(source, target, 100, "EUR")).andExpect(status().isCreated());
		transfer(user(alice), key, body(source, target, 200, "EUR"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code", is("IDEMPOTENCY_KEY_REUSED")));
		transfer(user(alice), key, body(source, otherTarget, 100, "EUR"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code", is("IDEMPOTENCY_KEY_REUSED")));

		assertThat(balance(source)).isEqualTo(900);
		assertThat(balance(otherTarget)).isZero();
		assertThat(transfersFrom(source)).isEqualTo(1);
	}

	@Test
	void sameKeyFromDifferentCallersDoesNotCollide() throws Exception {
		String key = newId("key");
		String alice = newId("alice");
		String carol = newId("carol");
		long aliceAccount = fundedAccount(alice, "EUR", 100);
		long carolAccount = fundedAccount(carol, "EUR", 100);
		long target = createAccount(newId("bob"), "EUR");

		transfer(user(alice), key, body(aliceAccount, target, 100, "EUR")).andExpect(status().isCreated());
		transfer(user(carol), key, body(carolAccount, target, 100, "EUR"))
				.andExpect(status().isCreated())
				.andExpect(header().doesNotExist("Idempotency-Replayed"));

		assertThat(balance(target)).isEqualTo(200);
	}

	@Test
	void aFailedRequestIsNotStoredSoTheKeyCanBeRetriedAfterTopUp() throws Exception {
		String alice = newId("alice");
		String key = newId("key");
		long source = fundedAccount(alice, "EUR", 50);
		long target = createAccount(newId("bob"), "EUR");

		transfer(user(alice), key, body(source, target, 100, "EUR"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code", is("INSUFFICIENT_FUNDS")));
		assertThat(keyRows(alice, key)).isZero();

		mockMvc.perform(post("/accounts/{id}/deposits", source).with(admin()).header("Idempotency-Key", newId("fund"))
				.contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100,\"currency\":\"EUR\"}"))
				.andExpect(status().isCreated());
		transfer(user(alice), key, body(source, target, 100, "EUR"))
				.andExpect(status().isCreated())
				.andExpect(header().doesNotExist("Idempotency-Replayed"));

		assertThat(balance(source)).isEqualTo(50);
		assertThat(balance(target)).isEqualTo(100);
		assertThat(keyRows(alice, key)).isEqualTo(1);
	}

	// --- concurrency ---

	private record Outcome(int status, String body, String replayedHeader) {
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

	@Test
	void concurrentRequestsWithTheSameKeyMoveMoneyExactlyOnce() throws Exception {
		String alice = newId("alice");
		String key = newId("key");
		long source = fundedAccount(alice, "EUR", 10_000);
		long target = createAccount(newId("bob"), "EUR");
		int threads = 8;
		List<MockHttpServletRequestBuilder> requests = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			requests.add(transferRequest(user(alice), key, body(source, target, 1_000, "EUR")));
		}

		List<Outcome> outcomes = runConcurrently(requests);

		assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.status()).isEqualTo(201));
		assertThat(outcomes.stream().map(Outcome::body).distinct()).hasSize(1);
		assertThat(outcomes.stream().filter(o -> o.replayedHeader() == null)).hasSize(1);
		assertThat(outcomes.stream().filter(o -> "true".equals(o.replayedHeader()))).hasSize(threads - 1);
		assertThat(transfersFrom(source)).isEqualTo(1);
		assertThat(balance(source)).isEqualTo(9_000);
		assertThat(balance(target)).isEqualTo(1_000);
		assertThat(keyRows(alice, key)).isEqualTo(1);
	}

	@Test
	void concurrentRequestsWithTheSameKeyAndDifferentBodiesLetExactlyOneWin() throws Exception {
		String alice = newId("alice");
		String key = newId("key");
		long source = fundedAccount(alice, "EUR", 10_000);
		long target = createAccount(newId("bob"), "EUR");
		int threads = 6;
		List<MockHttpServletRequestBuilder> requests = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			requests.add(transferRequest(user(alice), key, body(source, target, 100 + i, "EUR")));
		}

		List<Outcome> outcomes = runConcurrently(requests);

		assertThat(outcomes.stream().filter(o -> o.status() == 201)).hasSize(1);
		assertThat(outcomes.stream().filter(o -> o.status() == 422)).hasSize(threads - 1)
				.allSatisfy(o -> assertThat(o.body()).contains("IDEMPOTENCY_KEY_REUSED"));
		assertThat(transfersFrom(source)).isEqualTo(1);
	}

	@Test
	void concurrentRequestsWithTheSameKeyThatFailAllFailAndLeaveNothing() throws Exception {
		String alice = newId("alice");
		String key = newId("key");
		long source = fundedAccount(alice, "EUR", 10);
		long target = createAccount(newId("bob"), "EUR");
		int threads = 6;
		List<MockHttpServletRequestBuilder> requests = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			requests.add(transferRequest(user(alice), key, body(source, target, 500, "EUR")));
		}

		List<Outcome> outcomes = runConcurrently(requests);

		assertThat(outcomes).allSatisfy(outcome -> {
			assertThat(outcome.status()).isEqualTo(422);
			assertThat(outcome.body()).contains("INSUFFICIENT_FUNDS");
		});
		assertThat(keyRows(alice, key)).isZero();
		assertThat(balance(source)).isEqualTo(10);
	}

	@Test
	void concurrentTransfersWithDifferentKeysNeverOverdrawTheAccount() throws Exception {
		String alice = newId("alice");
		long funds = 1_000;
		long amount = 300;
		long source = fundedAccount(alice, "EUR", funds);
		long target = createAccount(newId("bob"), "EUR");
		int threads = 8;
		List<MockHttpServletRequestBuilder> requests = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			requests.add(transferRequest(user(alice), newId("key"), body(source, target, amount, "EUR")));
		}

		List<Outcome> outcomes = runConcurrently(requests);

		long successes = outcomes.stream().filter(o -> o.status() == 201).count();
		assertThat(outcomes.stream().filter(o -> o.status() == 422))
				.hasSize(threads - (int) successes)
				.allSatisfy(o -> assertThat(o.body()).contains("INSUFFICIENT_FUNDS"));
		assertThat(successes).isEqualTo(funds / amount);
		assertThat(balance(source)).isGreaterThanOrEqualTo(0).isEqualTo(funds - successes * amount);
		assertThat(balance(target)).isEqualTo(successes * amount);
		assertThat(transfersFrom(source)).isEqualTo(successes);
	}

	@Test
	void concurrentTransfersInOppositeDirectionsDoNotDeadlockAndConserveMoney() throws Exception {
		String alice = newId("alice");
		String bob = newId("bob");
		long a = fundedAccount(alice, "EUR", 5_000);
		long b = fundedAccount(bob, "EUR", 5_000);
		int perDirection = 6;
		List<MockHttpServletRequestBuilder> requests = new ArrayList<>();
		for (int i = 0; i < perDirection; i++) {
			requests.add(transferRequest(user(alice), newId("key"), body(a, b, 100, "EUR")));
			requests.add(transferRequest(user(bob), newId("key"), body(b, a, 100, "EUR")));
		}

		List<Outcome> outcomes = runConcurrently(requests);

		assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.status()).isEqualTo(201));
		assertThat(balance(a)).isEqualTo(5_000);
		assertThat(balance(b)).isEqualTo(5_000);
		assertThat(transfersFrom(a)).isEqualTo(perDirection);
		assertThat(transfersFrom(b)).isEqualTo(perDirection);
	}

}
