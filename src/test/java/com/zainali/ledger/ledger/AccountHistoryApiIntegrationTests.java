package com.zainali.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;
import com.zainali.ledger.TestcontainersConfiguration;

/** {@code GET /accounts/{id}/balance} and {@code GET /accounts/{id}/transactions}. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AccountHistoryApiIntegrationTests {

	private static final String SIGNED = "CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbc;

	private static String newId(String prefix) {
		return prefix + "-" + UUID.randomUUID();
	}

	private static RequestPostProcessor user(String subject) {
		return jwt().jwt(jwt -> jwt.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private static RequestPostProcessor admin() {
		return jwt().jwt(jwt -> jwt.subject(newId("admin"))).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
	}

	private long createAccount(String owner, String currency) throws Exception {
		String location = mockMvc.perform(post("/accounts").with(user(owner)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"currency\":\"" + currency + "\"}")).andExpect(status().isCreated())
				.andReturn().getResponse().getHeader("Location");
		return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
	}

	private void deposit(long account, long amount, String currency) throws Exception {
		mockMvc.perform(post("/accounts/{id}/deposits", account).with(admin()).header("Idempotency-Key", newId("k"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":" + amount + ",\"currency\":\"" + currency + "\"}")).andExpect(status().isCreated());
	}

	private void transfer(String owner, long source, long target, long amount, String currency) throws Exception {
		mockMvc.perform(post("/transfers").with(user(owner)).header("Idempotency-Key", newId("k"))
				.contentType(MediaType.APPLICATION_JSON).content("{\"sourceAccountId\":" + source + ",\"targetAccountId\":" + target
						+ ",\"amount\":" + amount + ",\"currency\":\"" + currency + "\"}")).andExpect(status().isCreated());
	}

	private ResultActions balance(RequestPostProcessor caller, long account) throws Exception {
		return mockMvc.perform(get("/accounts/{id}/balance", account).with(caller));
	}

	private ResultActions history(RequestPostProcessor caller, long account, String query) throws Exception {
		return mockMvc.perform(get("/accounts/{id}/transactions" + query, account).with(caller));
	}

	private long entrySum(long account) {
		return jdbc.sql("SELECT COALESCE(SUM(" + SIGNED + "), 0) FROM ledger_entries WHERE account_id = :id")
				.param("id", account).query(Long.class).single();
	}

	/** An account owned by {@code owner} with {@code count} deposits of 100, 200, ... in that order. */
	private long accountWithDeposits(String owner, int count) throws Exception {
		long account = createAccount(owner, "EUR");
		for (int i = 1; i <= count; i++) {
			deposit(account, i * 100L, "EUR");
		}
		return account;
	}

	// --- balance ---

	@Test
	void balanceOfAnEmptyAccountIsZero() throws Exception {
		String owner = newId("alice");
		long account = createAccount(owner, "USD");

		balance(user(owner), account)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accountId", is((int) account)))
				.andExpect(jsonPath("$.currency", is("USD")))
				.andExpect(jsonPath("$.balance", is(0)));
		assertThat(entrySum(account)).isZero();
	}

	@Test
	void balanceEqualsTheSumOfLedgerEntriesAfterDepositsAndTransfers() throws Exception {
		String alice = newId("alice");
		String bob = newId("bob");
		long aliceAccount = createAccount(alice, "EUR");
		long bobAccount = createAccount(bob, "EUR");
		deposit(aliceAccount, 5_000, "EUR");
		deposit(aliceAccount, 250, "EUR");
		transfer(alice, aliceAccount, bobAccount, 1_200, "EUR");
		transfer(bob, bobAccount, aliceAccount, 300, "EUR");

		balance(user(alice), aliceAccount).andExpect(status().isOk()).andExpect(jsonPath("$.balance", is(4_350)));
		balance(user(bob), bobAccount).andExpect(status().isOk()).andExpect(jsonPath("$.balance", is(900)));
		assertThat(entrySum(aliceAccount)).isEqualTo(4_350);
		assertThat(entrySum(bobAccount)).isEqualTo(900);
	}

	@Test
	void adminCanReadAnyBalance() throws Exception {
		long account = accountWithDeposits(newId("alice"), 1);

		balance(admin(), account).andExpect(status().isOk()).andExpect(jsonPath("$.balance", is(100)));
	}

	@Test
	void balanceOfSomeoneElsesAccountIsNotFound() throws Exception {
		long account = accountWithDeposits(newId("alice"), 1);

		balance(user(newId("mallory")), account)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code", is("NOT_FOUND")));
	}

	@Test
	void balanceOfAMissingAccountLooksTheSameAsAHiddenOne() throws Exception {
		long hidden = accountWithDeposits(newId("alice"), 1);
		RequestPostProcessor mallory = user(newId("mallory"));

		String hiddenBody = balance(mallory, hidden).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
		String missingBody = balance(mallory, Long.MAX_VALUE).andExpect(status().isNotFound()).andReturn().getResponse()
				.getContentAsString();

		assertThat(JsonPath.<String>read(missingBody, "$.detail")).isEqualTo(JsonPath.<String>read(hiddenBody, "$.detail"));
		assertThat(JsonPath.<String>read(missingBody, "$.code")).isEqualTo(JsonPath.<String>read(hiddenBody, "$.code"));
	}

	@Test
	void externalAccountBalanceIsNotVisibleToAUser() throws Exception {
		long external = jdbc.sql("SELECT id FROM accounts WHERE type = 'EXTERNAL' AND currency = 'EUR'").query(Long.class).single();

		balance(user(newId("alice")), external).andExpect(status().isNotFound());
		balance(admin(), external).andExpect(status().isOk());
	}

	@Test
	void balanceRequiresAuthentication() throws Exception {
		mockMvc.perform(get("/accounts/1/balance")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/accounts/1/transactions")).andExpect(status().isUnauthorized());
	}

	// --- transactions: basics ---

	@Test
	void historyOfAnEmptyAccountIsAnEmptyPage() throws Exception {
		String owner = newId("alice");
		long account = createAccount(owner, "EUR");

		history(user(owner), account, "")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content", empty()))
				.andExpect(jsonPath("$.page", is(0)))
				.andExpect(jsonPath("$.size", is(20)))
				.andExpect(jsonPath("$.totalElements", is(0)))
				.andExpect(jsonPath("$.totalPages", is(0)));
	}

	@Test
	void historyListsEntriesNewestFirstWithTheirFields() throws Exception {
		String owner = newId("alice");
		long account = accountWithDeposits(owner, 3);

		history(user(owner), account, "")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[*].amount", contains(300, 200, 100)))
				.andExpect(jsonPath("$.content[0].direction", is("CREDIT")))
				.andExpect(jsonPath("$.content[0].currency", is("EUR")))
				.andExpect(jsonPath("$.content[0].transferId").isNumber())
				.andExpect(jsonPath("$.content[0].id").isNumber())
				.andExpect(jsonPath("$.content[0].createdAt").isString())
				.andExpect(jsonPath("$.totalElements", is(3)))
				.andExpect(jsonPath("$.totalPages", is(1)));
	}

	@Test
	void historyShowsDebitsAndCreditsAndOnlyThisAccountsEntries() throws Exception {
		String alice = newId("alice");
		String bob = newId("bob");
		long aliceAccount = createAccount(alice, "EUR");
		long bobAccount = createAccount(bob, "EUR");
		deposit(aliceAccount, 1_000, "EUR");
		transfer(alice, aliceAccount, bobAccount, 400, "EUR");

		history(user(alice), aliceAccount, "")
				.andExpect(jsonPath("$.totalElements", is(2)))
				.andExpect(jsonPath("$.content[0].direction", is("DEBIT")))
				.andExpect(jsonPath("$.content[0].amount", is(400)))
				.andExpect(jsonPath("$.content[1].direction", is("CREDIT")));
		history(user(bob), bobAccount, "")
				.andExpect(jsonPath("$.totalElements", is(1)))
				.andExpect(jsonPath("$.content[0].direction", is("CREDIT")));
	}

	@Test
	void signedSumOfAllPagesEqualsTheBalance() throws Exception {
		String alice = newId("alice");
		String bob = newId("bob");
		long aliceAccount = createAccount(alice, "EUR");
		long bobAccount = createAccount(bob, "EUR");
		for (int i = 1; i <= 7; i++) {
			deposit(aliceAccount, i * 1_000L, "EUR");
		}
		transfer(alice, aliceAccount, bobAccount, 1_500, "EUR");
		transfer(alice, aliceAccount, bobAccount, 2_500, "EUR");

		long signed = 0;
		List<Integer> seen = new ArrayList<>();
		for (int page = 0; page < 3; page++) {
			String body = history(user(alice), aliceAccount, "?size=4&page=" + page).andExpect(status().isOk()).andReturn()
					.getResponse().getContentAsString();
			List<String> directions = JsonPath.read(body, "$.content[*].direction");
			List<Integer> amounts = JsonPath.read(body, "$.content[*].amount");
			List<Integer> ids = JsonPath.read(body, "$.content[*].id");
			seen.addAll(ids);
			for (int i = 0; i < amounts.size(); i++) {
				signed += "CREDIT".equals(directions.get(i)) ? amounts.get(i) : -amounts.get(i);
			}
		}

		assertThat(seen).hasSize(9).doesNotHaveDuplicates();
		long cached = ((Number) JsonPath.read(balance(user(alice), aliceAccount).andReturn().getResponse().getContentAsString(),
				"$.balance")).longValue();
		assertThat(signed).isEqualTo(cached).isEqualTo(entrySum(aliceAccount));
	}

	// --- transactions: pagination ---

	@Test
	void pagesSplitTheEntriesAndReportTotals() throws Exception {
		String owner = newId("alice");
		long account = accountWithDeposits(owner, 5);

		history(user(owner), account, "?size=2&page=0")
				.andExpect(jsonPath("$.content[*].amount", contains(500, 400)))
				.andExpect(jsonPath("$.totalElements", is(5)))
				.andExpect(jsonPath("$.totalPages", is(3)));
		history(user(owner), account, "?size=2&page=2")
				.andExpect(jsonPath("$.content[*].amount", contains(100)))
				.andExpect(jsonPath("$.page", is(2)))
				.andExpect(jsonPath("$.size", is(2)));
	}

	@Test
	void pageSizeIsCappedAtOneHundred() throws Exception {
		String owner = newId("alice");
		long account = accountWithDeposits(owner, 1);

		history(user(owner), account, "?size=100000")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.size", is(100)));
	}

	@Test
	void pageBeyondTheEndIsAnEmptyPageWithTheRealTotals() throws Exception {
		String owner = newId("alice");
		long account = accountWithDeposits(owner, 3);

		history(user(owner), account, "?page=1000000")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content", empty()))
				.andExpect(jsonPath("$.page", is(1000000)))
				.andExpect(jsonPath("$.totalElements", is(3)))
				.andExpect(jsonPath("$.totalPages", is(1)));
	}

	@Test
	void hugePageNumbersDoNotOverflow() throws Exception {
		String owner = newId("alice");
		long account = accountWithDeposits(owner, 1);

		history(user(owner), account, "?page=2147483647&size=100")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content", empty()))
				.andExpect(jsonPath("$.totalElements", is(1)));
		history(user(owner), account, "?page=99999999999999999999")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
	}

	@Test
	void invalidPageAndSizeAreRejected() throws Exception {
		String owner = newId("alice");
		long account = accountWithDeposits(owner, 1);

		for (String query : List.of("?page=-1", "?size=0", "?size=-5", "?page=abc", "?size=abc", "?page=1.5")) {
			history(user(owner), account, query)
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
		}
	}

	// --- transactions: sorting ---

	@Test
	void sortsByCreatedAtAscending() throws Exception {
		String owner = newId("alice");
		long account = accountWithDeposits(owner, 3);

		history(user(owner), account, "?sort=createdAt,asc")
				.andExpect(jsonPath("$.content[*].amount", contains(100, 200, 300)));
		history(user(owner), account, "?sort=createdAt,desc")
				.andExpect(jsonPath("$.content[*].amount", contains(300, 200, 100)));
	}

	@Test
	void sortsByAmountBothWays() throws Exception {
		String owner = newId("alice");
		long account = createAccount(owner, "EUR");
		deposit(account, 300, "EUR");
		deposit(account, 100, "EUR");
		deposit(account, 200, "EUR");

		history(user(owner), account, "?sort=amount,asc")
				.andExpect(jsonPath("$.content[*].amount", contains(100, 200, 300)));
		history(user(owner), account, "?sort=amount,desc")
				.andExpect(jsonPath("$.content[*].amount", contains(300, 200, 100)));
		history(user(owner), account, "?sort=amount")
				.andExpect(jsonPath("$.content[*].amount", contains(100, 200, 300)));
	}

	@Test
	void equalSortKeysStillPageStablyWithoutDuplicates() throws Exception {
		String owner = newId("alice");
		long account = createAccount(owner, "EUR");
		for (int i = 0; i < 6; i++) {
			deposit(account, 100, "EUR");
		}

		List<Integer> ids = new ArrayList<>();
		for (int page = 0; page < 3; page++) {
			String body = history(user(owner), account, "?sort=amount,asc&size=2&page=" + page).andReturn().getResponse()
					.getContentAsString();
			ids.addAll(JsonPath.read(body, "$.content[*].id"));
		}

		assertThat(ids).hasSize(6).doesNotHaveDuplicates();
	}

	@Test
	void unknownSortFieldOrDirectionIsRejected() throws Exception {
		String owner = newId("alice");
		long account = accountWithDeposits(owner, 1);

		for (String query : List.of("?sort=balance", "?sort=amount,sideways", "?sort=amount,asc,extra", "?sort=", "?sort=id;drop")) {
			history(user(owner), account, query)
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
		}
	}

	// --- transactions: date range ---

	private List<String> createdAts(String owner, long account) throws Exception {
		String body = history(user(owner), account, "?sort=createdAt,asc").andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.content[*].createdAt");
	}

	@Test
	void fromIsInclusiveAndToIsExclusive() throws Exception {
		String owner = newId("alice");
		long account = accountWithDeposits(owner, 4);
		List<String> times = createdAts(owner, account);

		history(user(owner), account, "?sort=createdAt,asc&from=" + times.get(1))
				.andExpect(jsonPath("$.content[*].amount", contains(200, 300, 400)))
				.andExpect(jsonPath("$.totalElements", is(3)));
		history(user(owner), account, "?sort=createdAt,asc&to=" + times.get(2))
				.andExpect(jsonPath("$.content[*].amount", contains(100, 200)))
				.andExpect(jsonPath("$.totalElements", is(2)));
		history(user(owner), account, "?sort=createdAt,asc&from=" + times.get(1) + "&to=" + times.get(3))
				.andExpect(jsonPath("$.content[*].amount", contains(200, 300)));
	}

	@Test
	void rangeWithNoEntriesIsEmpty() throws Exception {
		String owner = newId("alice");
		long account = accountWithDeposits(owner, 2);
		Instant future = Instant.now().plusSeconds(3_600);

		history(user(owner), account, "?from=" + future)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content", empty()))
				.andExpect(jsonPath("$.totalElements", is(0)));
		history(user(owner), account, "?from=" + future + "&to=" + future.plusSeconds(60))
				.andExpect(jsonPath("$.totalElements", is(0)));
	}

	@Test
	void fromEqualToToIsAnEmptyRange() throws Exception {
		String owner = newId("alice");
		long account = accountWithDeposits(owner, 2);
		String time = createdAts(owner, account).get(0);

		history(user(owner), account, "?from=" + time + "&to=" + time)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements", is(0)));
	}

	@Test
	void invalidDatesAreRejected() throws Exception {
		String owner = newId("alice");
		long account = accountWithDeposits(owner, 1);

		for (String query : List.of("?from=yesterday", "?to=2026-13-45T00:00:00Z", "?from=2026-02-30T00:00:00Z",
				"?from=1700000000", "?from=2026-01-01")) {
			history(user(owner), account, query)
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
		}
	}

	@Test
	void fromAfterToIsRejected() throws Exception {
		String owner = newId("alice");
		long account = accountWithDeposits(owner, 1);

		history(user(owner), account, "?from=2026-06-01T00:00:00Z&to=2026-01-01T00:00:00Z")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
	}

	// --- transactions: visibility ---

	@Test
	void historyOfSomeoneElsesAccountIsNotFound() throws Exception {
		long account = accountWithDeposits(newId("alice"), 1);

		history(user(newId("mallory")), account, "")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code", is("NOT_FOUND")));
	}

	@Test
	void hiddenAndMissingAccountsGiveTheSameHistoryResponse() throws Exception {
		long hidden = accountWithDeposits(newId("alice"), 1);
		RequestPostProcessor mallory = user(newId("mallory"));

		String hiddenBody = history(mallory, hidden, "").andExpect(status().isNotFound()).andReturn().getResponse()
				.getContentAsString();
		String missingBody = history(mallory, Long.MAX_VALUE, "").andExpect(status().isNotFound()).andReturn().getResponse()
				.getContentAsString();

		assertThat(JsonPath.<String>read(missingBody, "$.detail")).isEqualTo(JsonPath.<String>read(hiddenBody, "$.detail"));
		assertThat(JsonPath.<String>read(missingBody, "$.code")).isEqualTo(JsonPath.<String>read(hiddenBody, "$.code"));
	}

	@Test
	void adminCanReadAnyHistoryIncludingTheExternalAccount() throws Exception {
		long account = accountWithDeposits(newId("alice"), 2);
		long external = jdbc.sql("SELECT id FROM accounts WHERE type = 'EXTERNAL' AND currency = 'EUR'").query(Long.class).single();

		history(admin(), account, "").andExpect(status().isOk()).andExpect(jsonPath("$.totalElements", is(2)));
		history(admin(), external, "?size=1").andExpect(status().isOk()).andExpect(jsonPath("$.content[0].direction", is("DEBIT")));
		history(user(newId("alice")), external, "").andExpect(status().isNotFound());
	}

}
