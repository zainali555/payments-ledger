package com.zainali.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.zainali.ledger.TestcontainersConfiguration;

/**
 * Milestone 7: many transfers at once on shared accounts. Accounts are locked with {@code SELECT ... FOR UPDATE} in
 * ascending id order (DECISIONS 8). A Postgres deadlock (SQLSTATE 40P01) would abort one transaction and reach the
 * catch-all handler as a 500, so "every response is 201 or an expected 422" is the deadlock check; the per-request
 * timeout turns a hang into a failure.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TransferConcurrencyIntegrationTests {

	private static final String SIGNED = "CASE e.direction WHEN 'CREDIT' THEN e.amount ELSE -e.amount END";
	private static final long REQUEST_TIMEOUT_SECONDS = 60;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbc;

	// --- helpers ---

	private record Account(String owner, long id) {
	}

	private record Outcome(int status, String body) {
	}

	private static String newId(String prefix) {
		return prefix + "-" + UUID.randomUUID();
	}

	private static RequestPostProcessor user(String subject) {
		return jwt().jwt(jwt -> jwt.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private static RequestPostProcessor admin() {
		return jwt().jwt(jwt -> jwt.subject(newId("admin"))).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
	}

	private Account fundedAccount(long funds) throws Exception {
		String owner = newId("owner");
		String location = mockMvc.perform(post("/accounts").with(user(owner)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"currency\":\"EUR\"}")).andExpect(status().isCreated()).andReturn().getResponse()
				.getHeader("Location");
		long id = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
		if (funds > 0) {
			mockMvc.perform(post("/accounts/{id}/deposits", id).with(admin()).header("Idempotency-Key", newId("fund"))
					.contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + funds + ",\"currency\":\"EUR\"}"))
					.andExpect(status().isCreated());
		}
		return new Account(owner, id);
	}

	/** A transfer sent by the owner of the source account, with a fresh idempotency key. */
	private MockHttpServletRequestBuilder transfer(Account source, Account target, long amount) {
		return post("/transfers").with(user(source.owner())).header("Idempotency-Key", newId("key"))
				.contentType(MediaType.APPLICATION_JSON).content("{\"sourceAccountId\":" + source.id()
						+ ",\"targetAccountId\":" + target.id() + ",\"amount\":" + amount + ",\"currency\":\"EUR\"}");
	}

	/** Releases every request at once and waits for all of them, each bounded by a timeout. */
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
					return new Outcome(result.getResponse().getStatus(), result.getResponse().getContentAsString());
				};
				futures.add(pool.submit(task));
			}
			ready.await();
			go.countDown();
			List<Outcome> outcomes = new ArrayList<>();
			for (Future<Outcome> future : futures) {
				outcomes.add(future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
			}
			return outcomes;
		}
		finally {
			pool.shutdownNow();
		}
	}

	private static List<Long> ids(List<Account> accounts) {
		return accounts.stream().map(Account::id).toList();
	}

	private long balance(Account account) {
		return jdbc.sql("SELECT balance FROM accounts WHERE id = :id").param("id", account.id()).query(Long.class).single();
	}

	private long totalBalance(List<Account> accounts) {
		return jdbc.sql("SELECT COALESCE(SUM(balance), 0) FROM accounts WHERE id IN (:ids)").param("ids", ids(accounts))
				.query(Long.class).single();
	}

	private long transfersAmong(List<Account> accounts) {
		return jdbc.sql("SELECT count(*) FROM transfers WHERE type = 'TRANSFER' AND source_account_id IN (:ids) "
				+ "AND target_account_id IN (:ids)").param("ids", ids(accounts)).query(Long.class).single();
	}

	/** Every transfer touching the accounts has exactly one debit and one credit that sum to zero. */
	private void assertEveryTransferIsBalanced(List<Account> accounts) {
		List<Long> unbalanced = jdbc.sql("SELECT t.id FROM transfers t LEFT JOIN ledger_entries e ON e.transfer_id = t.id "
				+ "WHERE t.source_account_id IN (:ids) OR t.target_account_id IN (:ids) "
				+ "GROUP BY t.id HAVING COALESCE(SUM(" + SIGNED + "), 0) <> 0 OR COUNT(e.id) <> 2")
				.param("ids", ids(accounts)).query(Long.class).list();
		assertThat(unbalanced).as("transfers whose entries are not one debit and one credit summing to zero").isEmpty();
	}

	/** The cached balance of every account equals the signed sum of its entries. */
	private void assertBalancesMatchEntrySums(List<Account> accounts) {
		List<Long> drifting = jdbc.sql("SELECT a.id FROM accounts a LEFT JOIN ledger_entries e ON e.account_id = a.id "
				+ "WHERE a.id IN (:ids) GROUP BY a.id, a.balance HAVING a.balance <> COALESCE(SUM(" + SIGNED + "), 0)")
				.param("ids", ids(accounts)).query(Long.class).list();
		assertThat(drifting).as("accounts whose cached balance differs from their entry sum").isEmpty();
	}

	// --- tests ---

	/**
	 * 50 transfers at once on one hot account: each of 25 counterparts sends to it and receives from it at the same
	 * time, so every pair has transfers in both directions in flight (a deadlock without a global lock order).
	 * Counterpart ids are both lower and higher than the hot account's. The hot account cannot afford every outgoing
	 * transfer, so some fail with INSUFFICIENT_FUNDS depending on the interleaving.
	 */
	@Test
	void fiftyConcurrentTransfersOnOneAccountNeitherCreateNorLoseMoney() throws Exception {
		int counterpartCount = 25;
		long hotFunds = 2_000;
		long counterpartFunds = 1_000;
		long incoming = 200;
		long outgoing = 300;

		// Created before and after the hot account, so lower and higher ids.
		List<Account> counterparts = new ArrayList<>();
		for (int i = 0; i < counterpartCount / 2; i++) {
			counterparts.add(fundedAccount(counterpartFunds));
		}
		Account hot = fundedAccount(hotFunds);
		while (counterparts.size() < counterpartCount) {
			counterparts.add(fundedAccount(counterpartFunds));
		}
		assertThat(counterparts).anyMatch(a -> a.id() < hot.id()).anyMatch(a -> a.id() > hot.id());

		List<Account> involved = new ArrayList<>(counterparts);
		involved.add(hot);
		long totalBefore = totalBalance(involved);
		assertThat(totalBefore).isEqualTo(hotFunds + counterpartCount * counterpartFunds);

		List<MockHttpServletRequestBuilder> requests = new ArrayList<>();
		for (Account counterpart : counterparts) {
			requests.add(transfer(counterpart, hot, incoming));
			requests.add(transfer(hot, counterpart, outgoing));
		}
		assertThat(requests).hasSize(50);

		List<Outcome> outcomes = runConcurrently(requests);

		List<Outcome> in = new ArrayList<>();
		List<Outcome> out = new ArrayList<>();
		for (int i = 0; i < outcomes.size(); i++) {
			(i % 2 == 0 ? in : out).add(outcomes.get(i));
		}
		// No deadlock, lock or optimistic-locking error: every response is a success or the expected business rejection.
		assertThat(in).as("incoming transfers (each counterpart can always afford its one)")
				.allSatisfy(o -> assertThat(o.status()).as(o.body()).isEqualTo(201));
		assertThat(out).as("outgoing transfers").allSatisfy(o -> {
			if (o.status() != 201) {
				assertThat(o.status()).as(o.body()).isEqualTo(422);
				assertThat(o.body()).contains("INSUFFICIENT_FUNDS");
			}
		});
		long outOk = out.stream().filter(o -> o.status() == 201).count();
		// The hot account can never pay for all 25: 2,000 + 25 x 200 = 7,000 < 25 x 300 = 7,500.
		assertThat(outOk).isBetween(hotFunds / outgoing, (long) counterpartCount - 1);

		// No money created or lost.
		assertThat(totalBalance(involved)).as("sum of all involved balances").isEqualTo(totalBefore);
		assertThat(balance(hot)).isGreaterThanOrEqualTo(0)
				.isEqualTo(hotFunds + counterpartCount * incoming - outOk * outgoing);
		long paidCounterparts = counterparts.stream()
				.filter(c -> balance(c) == counterpartFunds - incoming + outgoing).count();
		assertThat(counterparts).allSatisfy(c -> assertThat(balance(c))
				.isIn(counterpartFunds - incoming, counterpartFunds - incoming + outgoing));
		assertThat(paidCounterparts).isEqualTo(outOk);

		// Balances match the entries, and failed transfers left nothing behind.
		assertThat(transfersAmong(involved)).isEqualTo(counterpartCount + outOk);
		assertEveryTransferIsBalanced(involved);
		assertBalancesMatchEntrySums(involved);
	}

	/**
	 * Every ordered pair among several accounts at once, twice, so each pair has transfers in both directions running
	 * together. Without a global lock order this is the classic deadlock (A locks 1 then waits for 2, B locks 2 then
	 * waits for 1), and with five accounts there are also longer cycles (1 -> 2 -> 3 -> 1). Every account can afford
	 * everything it sends, so every request must succeed and every balance ends where it started.
	 */
	@Test
	void oppositeDirectionTransfersAmongSeveralAccountsDoNotDeadlockAndConserveMoney() throws Exception {
		int accountCount = 5;
		int rounds = 2;
		long amount = 100;
		long funds = (accountCount - 1) * rounds * amount; // enough to send everything before receiving anything

		List<Account> accounts = new ArrayList<>();
		for (int i = 0; i < accountCount; i++) {
			accounts.add(fundedAccount(funds));
		}
		long totalBefore = totalBalance(accounts);

		List<MockHttpServletRequestBuilder> requests = new ArrayList<>();
		for (int round = 0; round < rounds; round++) {
			for (Account source : accounts) {
				for (Account target : accounts) {
					if (source != target) {
						requests.add(transfer(source, target, amount));
					}
				}
			}
		}
		int expected = accountCount * (accountCount - 1) * rounds;
		assertThat(requests).hasSize(expected);

		List<Outcome> outcomes = runConcurrently(requests);

		assertThat(outcomes).allSatisfy(o -> assertThat(o.status()).as(o.body()).isEqualTo(201));
		assertThat(totalBalance(accounts)).isEqualTo(totalBefore);
		assertThat(accounts).allSatisfy(a -> assertThat(balance(a)).isEqualTo(funds));
		assertThat(transfersAmong(accounts)).isEqualTo(expected);
		assertEveryTransferIsBalanced(accounts);
		assertBalancesMatchEntrySums(accounts);
	}

}
