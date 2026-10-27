package com.zainali.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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
class DepositApiIntegrationTests {

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

	private static String body(long amount, String currency) {
		return "{\"amount\":" + amount + ",\"currency\":\"" + currency + "\"}";
	}

	private long createAccount(String currency) throws Exception {
		String location = mockMvc.perform(post("/accounts").with(user(newId("user")))
				.contentType(MediaType.APPLICATION_JSON).content("{\"currency\":\"" + currency + "\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getHeader("Location");
		return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
	}

	private MockHttpServletRequestBuilder depositRequest(RequestPostProcessor caller, long accountId, String key,
			String body) {
		MockHttpServletRequestBuilder request = post("/accounts/{id}/deposits", accountId).with(caller)
				.contentType(MediaType.APPLICATION_JSON).content(body);
		return key == null ? request : request.header("Idempotency-Key", key);
	}

	private ResultActions deposit(RequestPostProcessor caller, long accountId, String key, String body) throws Exception {
		return mockMvc.perform(depositRequest(caller, accountId, key, body));
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

	private long transfersInto(long accountId) {
		return jdbc.sql("SELECT count(*) FROM transfers WHERE target_account_id = :id").param("id", accountId)
				.query(Long.class).single();
	}

	private long keyRows(String owner, String key) {
		return jdbc.sql("SELECT count(*) FROM idempotency_keys WHERE owner_id = :o AND key = :k").param("o", owner)
				.param("k", key).query(Long.class).single();
	}

	// --- success ---

	@Test
	void adminDepositCreditsTheAccountAndDebitsExternal() throws Exception {
		long account = createAccount("EUR");
		long externalBefore = externalBalance("EUR");

		deposit(admin(), account, newId("key"), body(2_500, "EUR"))
				.andExpect(status().isCreated())
				.andExpect(header().doesNotExist("Idempotency-Replayed"))
				.andExpect(jsonPath("$.id", notNullValue()))
				.andExpect(jsonPath("$.type", is("DEPOSIT")))
				.andExpect(jsonPath("$.status", is("COMPLETED")))
				.andExpect(jsonPath("$.sourceAccountId", is((int) externalId("EUR"))))
				.andExpect(jsonPath("$.targetAccountId", is((int) account)))
				.andExpect(jsonPath("$.amount", is(2_500)))
				.andExpect(jsonPath("$.currency", is("EUR")))
				.andExpect(jsonPath("$.createdAt", notNullValue()));

		assertThat(balance(account)).isEqualTo(2_500);
		assertThat(externalBalance("EUR")).isEqualTo(externalBefore - 2_500);
	}

	@Test
	void depositWritesOneDebitAndOneCreditThatSumToZero() throws Exception {
		long account = createAccount("EUR");

		deposit(admin(), account, newId("key"), body(700, "EUR")).andExpect(status().isCreated());

		long transferId = jdbc.sql("SELECT id FROM transfers WHERE target_account_id = :id").param("id", account)
				.query(Long.class).single();
		var entries = jdbc.sql("SELECT account_id, direction, amount FROM ledger_entries WHERE transfer_id = :t")
				.param("t", transferId).query().listOfRows();
		assertThat(entries).hasSize(2);
		assertThat(entries).anySatisfy(e -> {
			assertThat(e.get("direction")).isEqualTo("DEBIT");
			assertThat(e.get("account_id")).isEqualTo(externalId("EUR"));
			assertThat(e.get("amount")).isEqualTo(700L);
		});
		assertThat(entries).anySatisfy(e -> {
			assertThat(e.get("direction")).isEqualTo("CREDIT");
			assertThat(e.get("account_id")).isEqualTo(account);
			assertThat(e.get("amount")).isEqualTo(700L);
		});
	}

	@Test
	void usdDepositUsesTheUsdExternalAccount() throws Exception {
		long account = createAccount("USD");
		long eurBefore = externalBalance("EUR");
		long usdBefore = externalBalance("USD");

		deposit(admin(), account, newId("key"), body(1_000, "USD"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.sourceAccountId", is((int) externalId("USD"))));

		assertThat(externalBalance("USD")).isEqualTo(usdBefore - 1_000);
		assertThat(externalBalance("EUR")).isEqualTo(eurBefore);
	}

	@Test
	void depositsAccumulate() throws Exception {
		long account = createAccount("EUR");

		deposit(admin(), account, newId("key"), body(100, "EUR")).andExpect(status().isCreated());
		deposit(admin(), account, newId("key"), body(250, "EUR")).andExpect(status().isCreated());

		assertThat(balance(account)).isEqualTo(350);
	}

	@Test
	void amountOfExactlyTheCapIsAccepted() throws Exception {
		long account = createAccount("EUR");

		deposit(admin(), account, newId("key"), body(1_000_000_000_000L, "EUR")).andExpect(status().isCreated());

		assertThat(balance(account)).isEqualTo(1_000_000_000_000L);
	}

	// --- security ---

	@Test
	void userCannotDeposit() throws Exception {
		long account = createAccount("EUR");

		deposit(user(newId("user")), account, newId("key"), body(100, "EUR")).andExpect(status().isForbidden());

		assertThat(balance(account)).isZero();
	}

	@Test
	void depositWithoutTokenReturns401() throws Exception {
		mockMvc.perform(post("/accounts/1/deposits").header("Idempotency-Key", "k")
				.contentType(MediaType.APPLICATION_JSON).content(body(100, "EUR")))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void tokenWithoutAnyRoleReturns403() throws Exception {
		deposit(jwt().jwt(jwt -> jwt.subject(newId("norole"))), 1, newId("key"), body(100, "EUR"))
				.andExpect(status().isForbidden());
	}

	// --- Idempotency-Key header ---

	@Test
	void missingKeyIsRejected() throws Exception {
		long account = createAccount("EUR");

		deposit(admin(), account, null, body(100, "EUR"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code", is("IDEMPOTENCY_KEY_REQUIRED")));

		assertThat(balance(account)).isZero();
	}

	@Test
	void missingKeyIsReportedBeforeBodyProblems() throws Exception {
		deposit(admin(), 1, null, "{not json")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code", is("IDEMPOTENCY_KEY_REQUIRED")));
	}

	@Test
	void blankKeyIsRejected() throws Exception {
		long account = createAccount("EUR");

		deposit(admin(), account, "   ", body(100, "EUR"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code", is("IDEMPOTENCY_KEY_REQUIRED")));
	}

	@Test
	void keyLongerThan255CharactersIsRejected() throws Exception {
		long account = createAccount("EUR");

		deposit(admin(), account, "k".repeat(256), body(100, "EUR"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));

		assertThat(balance(account)).isZero();
	}

	@Test
	void keyOfExactly255CharactersIsAccepted() throws Exception {
		long account = createAccount("EUR");

		deposit(admin(), account, "k".repeat(255), body(100, "EUR")).andExpect(status().isCreated());
	}

	// --- validation ---

	@Test
	void invalidBodiesAreRejectedWithValidationFailed() throws Exception {
		long account = createAccount("EUR");
		List<String> bodies = List.of(
				body(0, "EUR"),
				body(-5, "EUR"),
				body(1_000_000_000_001L, "EUR"),
				"{\"currency\":\"EUR\"}",
				"{\"amount\":100}",
				"{\"amount\":100,\"currency\":\"GBP\"}",
				"{\"amount\":\"abc\",\"currency\":\"EUR\"}",
				"{\"amount\":10.5,\"currency\":\"EUR\"}",
				"{\"amount\":null,\"currency\":\"EUR\"}",
				"{}",
				"{not json");

		for (String body : bodies) {
			deposit(admin(), account, newId("key"), body)
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
		}

		assertThat(balance(account)).isZero();
		assertThat(transfersInto(account)).isZero();
	}

	@Test
	void nonNumericAccountIdIsRejected() throws Exception {
		mockMvc.perform(post("/accounts/abc/deposits").with(admin()).header("Idempotency-Key", "k")
				.contentType(MediaType.APPLICATION_JSON).content(body(100, "EUR")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
	}

	// --- business rules ---

	@Test
	void missingAccountReturns404() throws Exception {
		deposit(admin(), Long.MAX_VALUE, newId("key"), body(100, "EUR"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code", is("NOT_FOUND")));
	}

	@Test
	void depositIntoAnExternalAccountIsRejected() throws Exception {
		long externalBefore = externalBalance("EUR");

		deposit(admin(), externalId("EUR"), newId("key"), body(100, "EUR"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code", is("EXTERNAL_ACCOUNT_NOT_ALLOWED")));
		deposit(admin(), externalId("USD"), newId("key"), body(100, "EUR"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code", is("EXTERNAL_ACCOUNT_NOT_ALLOWED")));

		assertThat(externalBalance("EUR")).isEqualTo(externalBefore);
	}

	@Test
	void currencyMismatchIsRejected() throws Exception {
		long eurAccount = createAccount("EUR");
		long externalBefore = externalBalance("USD");

		deposit(admin(), eurAccount, newId("key"), body(100, "USD"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code", is("CURRENCY_MISMATCH")));

		assertThat(balance(eurAccount)).isZero();
		assertThat(externalBalance("USD")).isEqualTo(externalBefore);
	}

	// --- duplicates: sequential ---

	@Test
	void duplicateRequestReplaysTheOriginalResponseAndCreatesNothingNew() throws Exception {
		String adminSubject = newId("admin");
		String key = newId("key");
		long account = createAccount("EUR");
		long externalBefore = externalBalance("EUR");

		String first = deposit(admin(adminSubject), account, key, body(2_500, "EUR"))
				.andExpect(status().isCreated())
				.andExpect(header().doesNotExist("Idempotency-Replayed"))
				.andReturn().getResponse().getContentAsString();
		MvcResult second = deposit(admin(adminSubject), account, key, body(2_500, "EUR"))
				.andExpect(status().isCreated())
				.andExpect(header().string("Idempotency-Replayed", "true"))
				.andReturn();

		assertThat(second.getResponse().getContentAsString()).isEqualTo(first);
		assertThat(transfersInto(account)).isEqualTo(1);
		assertThat(balance(account)).isEqualTo(2_500);
		assertThat(externalBalance("EUR")).isEqualTo(externalBefore - 2_500);
		assertThat(jdbc.sql("SELECT count(*) FROM ledger_entries e JOIN transfers t ON t.id = e.transfer_id "
				+ "WHERE t.target_account_id = :id").param("id", account).query(Long.class).single()).isEqualTo(2);
	}

	@Test
	void replayIgnoresJsonFormattingDifferences() throws Exception {
		String adminSubject = newId("admin");
		String key = newId("key");
		long account = createAccount("EUR");

		deposit(admin(adminSubject), account, key, "{\"amount\":100,\"currency\":\"EUR\"}")
				.andExpect(status().isCreated());
		deposit(admin(adminSubject), account, key, "{ \"currency\" : \"EUR\",\n  \"amount\" : 100 }")
				.andExpect(status().isCreated())
				.andExpect(header().string("Idempotency-Replayed", "true"));

		assertThat(transfersInto(account)).isEqualTo(1);
	}

	@Test
	void sameKeyWithDifferentAmountIsRejectedAsReused() throws Exception {
		String adminSubject = newId("admin");
		String key = newId("key");
		long account = createAccount("EUR");

		deposit(admin(adminSubject), account, key, body(100, "EUR")).andExpect(status().isCreated());
		deposit(admin(adminSubject), account, key, body(200, "EUR"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code", is("IDEMPOTENCY_KEY_REUSED")));

		assertThat(balance(account)).isEqualTo(100);
		assertThat(transfersInto(account)).isEqualTo(1);
	}

	@Test
	void sameKeyForADifferentAccountIsRejectedAsReused() throws Exception {
		String adminSubject = newId("admin");
		String key = newId("key");
		long first = createAccount("EUR");
		long second = createAccount("EUR");

		deposit(admin(adminSubject), first, key, body(100, "EUR")).andExpect(status().isCreated());
		deposit(admin(adminSubject), second, key, body(100, "EUR"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code", is("IDEMPOTENCY_KEY_REUSED")));

		assertThat(balance(second)).isZero();
	}

	@Test
	void sameKeyFromDifferentCallersDoesNotCollide() throws Exception {
		String key = newId("key");
		long account = createAccount("EUR");

		deposit(admin(), account, key, body(100, "EUR")).andExpect(status().isCreated());
		deposit(admin(), account, key, body(100, "EUR"))
				.andExpect(status().isCreated())
				.andExpect(header().doesNotExist("Idempotency-Replayed"));

		assertThat(balance(account)).isEqualTo(200);
	}

	@Test
	void failedRequestIsNotStoredSoTheKeyCanBeRetried() throws Exception {
		String adminSubject = newId("admin");
		String key = newId("key");
		long account = createAccount("EUR");

		deposit(admin(adminSubject), Long.MAX_VALUE, key, body(100, "EUR")).andExpect(status().isNotFound());
		assertThat(keyRows(adminSubject, key)).isZero();

		// The same key now works, with a different request: nothing from the failure was remembered.
		deposit(admin(adminSubject), account, key, body(300, "EUR"))
				.andExpect(status().isCreated())
				.andExpect(header().doesNotExist("Idempotency-Replayed"));
		assertThat(balance(account)).isEqualTo(300);
		assertThat(keyRows(adminSubject, key)).isEqualTo(1);
	}

	@Test
	void businessRuleFailuresLeaveNoKeyRow() throws Exception {
		String adminSubject = newId("admin");
		String key = newId("key");
		long eurAccount = createAccount("EUR");

		deposit(admin(adminSubject), eurAccount, key, body(100, "USD"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code", is("CURRENCY_MISMATCH")));

		assertThat(keyRows(adminSubject, key)).isZero();
	}

	// --- duplicates: concurrent ---

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
	void concurrentRequestsWithTheSameKeyCreateExactlyOneDeposit() throws Exception {
		String adminSubject = newId("admin");
		String key = newId("key");
		long account = createAccount("EUR");
		long externalBefore = externalBalance("EUR");
		int threads = 8;
		List<MockHttpServletRequestBuilder> requests = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			requests.add(depositRequest(admin(adminSubject), account, key, body(1_000, "EUR")));
		}

		List<Outcome> outcomes = runConcurrently(requests);

		assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.status()).isEqualTo(201));
		assertThat(outcomes.stream().map(Outcome::body).distinct()).hasSize(1);
		assertThat(outcomes.stream().filter(o -> o.replayedHeader() == null)).hasSize(1);
		assertThat(outcomes.stream().filter(o -> "true".equals(o.replayedHeader()))).hasSize(threads - 1);
		assertThat(transfersInto(account)).isEqualTo(1);
		assertThat(balance(account)).isEqualTo(1_000);
		assertThat(externalBalance("EUR")).isEqualTo(externalBefore - 1_000);
		assertThat(keyRows(adminSubject, key)).isEqualTo(1);
	}

	@Test
	void concurrentRequestsWithTheSameKeyAndDifferentBodiesLetExactlyOneWin() throws Exception {
		String adminSubject = newId("admin");
		String key = newId("key");
		long account = createAccount("EUR");
		int threads = 6;
		List<MockHttpServletRequestBuilder> requests = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			requests.add(depositRequest(admin(adminSubject), account, key, body(100 + i, "EUR")));
		}

		List<Outcome> outcomes = runConcurrently(requests);

		assertThat(outcomes.stream().filter(o -> o.status() == 201)).hasSize(1);
		assertThat(outcomes.stream().filter(o -> o.status() == 422)).hasSize(threads - 1)
				.allSatisfy(o -> assertThat(o.body()).contains("IDEMPOTENCY_KEY_REUSED"));
		assertThat(transfersInto(account)).isEqualTo(1);
	}

	@Test
	void concurrentRequestsWithTheSameKeyThatFailAllFailAndLeaveNothing() throws Exception {
		String adminSubject = newId("admin");
		String key = newId("key");
		int threads = 6;
		List<MockHttpServletRequestBuilder> requests = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			requests.add(depositRequest(admin(adminSubject), Long.MAX_VALUE, key, body(100, "EUR")));
		}

		List<Outcome> outcomes = runConcurrently(requests);

		assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.status()).isEqualTo(404));
		assertThat(keyRows(adminSubject, key)).isZero();
	}

	@Test
	void concurrentDepositsWithDifferentKeysLoseNoUpdates() throws Exception {
		long account = createAccount("EUR");
		long externalBefore = externalBalance("EUR");
		int threads = 8;
		List<MockHttpServletRequestBuilder> requests = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			requests.add(depositRequest(admin(), account, newId("key"), body(100, "EUR")));
		}

		List<Outcome> outcomes = runConcurrently(requests);

		assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.status()).isEqualTo(201));
		assertThat(balance(account)).isEqualTo(threads * 100L);
		assertThat(externalBalance("EUR")).isEqualTo(externalBefore - threads * 100L);
		assertThat(transfersInto(account)).isEqualTo(threads);
	}

	// --- ledger invariants ---

	@Test
	void cachedBalancesMatchTheSumOfEntriesAndEntriesSumToZero() throws Exception {
		long account = createAccount("EUR");
		deposit(admin(), account, newId("key"), body(1_234, "EUR")).andExpect(status().isCreated());
		deposit(admin(), account, newId("key"), body(66, "EUR")).andExpect(status().isCreated());

		long signedSumAllEntries = jdbc.sql("SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END), 0) "
				+ "FROM ledger_entries").query(Long.class).single();
		assertThat(signedSumAllEntries).isZero();

		long entrySumForAccount = jdbc.sql("SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END), 0) "
				+ "FROM ledger_entries WHERE account_id = :id").param("id", account).query(Long.class).single();
		assertThat(balance(account)).isEqualTo(entrySumForAccount).isEqualTo(1_300);

		long externalEntries = jdbc.sql("SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END), 0) "
				+ "FROM ledger_entries WHERE account_id = :id").param("id", externalId("EUR")).query(Long.class).single();
		assertThat(externalBalance("EUR")).isEqualTo(externalEntries);
	}

}
