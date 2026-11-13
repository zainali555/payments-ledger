package com.zainali.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.zainali.ledger.TestcontainersConfiguration;

/**
 * The ledger invariants, checked over the whole database after a mix of successful, failed and replayed requests. The
 * checks cover every row, including rows left by other test classes that share the container, so they also act as a
 * safety net for the rest of the suite.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class LedgerInvariantsIntegrationTests {

	private static final String SIGNED = "CASE e.direction WHEN 'CREDIT' THEN e.amount ELSE -e.amount END";

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
				.content("{\"currency\":\"" + currency + "\"}")).andReturn().getResponse().getHeader("Location");
		return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
	}

	private void deposit(long account, long amount, String currency) throws Exception {
		mockMvc.perform(post("/accounts/{id}/deposits", account).with(admin()).header("Idempotency-Key", newId("k"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":" + amount + ",\"currency\":\"" + currency + "\"}"));
	}

	private void transfer(String owner, String key, long source, long target, long amount, String currency) throws Exception {
		mockMvc.perform(post("/transfers").with(user(owner)).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content("{\"sourceAccountId\":" + source + ",\"targetAccountId\":" + target
						+ ",\"amount\":" + amount + ",\"currency\":\"" + currency + "\"}"));
	}

	@Test
	void entriesSumToZeroPerTransferAndCachedBalancesEqualTheEntrySumForEveryAccount() throws Exception {
		String alice = newId("alice");
		String bob = newId("bob");
		long aliceEur = createAccount(alice, "EUR");
		long bobEur = createAccount(bob, "EUR");
		long aliceUsd = createAccount(alice, "USD");
		deposit(aliceEur, 5_000, "EUR");
		deposit(aliceUsd, 2_000, "USD");
		deposit(bobEur, 100, "EUR");

		String replayed = newId("key");
		transfer(alice, newId("key"), aliceEur, bobEur, 1_200, "EUR");
		transfer(alice, replayed, aliceEur, bobEur, 300, "EUR");
		transfer(alice, replayed, aliceEur, bobEur, 300, "EUR");
		transfer(bob, newId("key"), bobEur, aliceEur, 700, "EUR");
		transfer(bob, newId("key"), bobEur, aliceEur, 999_999, "EUR");
		transfer(alice, newId("key"), aliceEur, aliceUsd, 10, "EUR");
		transfer(alice, newId("key"), aliceEur, aliceEur, 10, "EUR");
		transfer(alice, newId("key"), aliceUsd, aliceEur, 2_000, "USD");

		List<Long> unbalancedTransfers = jdbc.sql("SELECT t.id FROM transfers t LEFT JOIN ledger_entries e ON e.transfer_id = t.id "
				+ "GROUP BY t.id HAVING COALESCE(SUM(" + SIGNED + "), 0) <> 0 OR COUNT(e.id) <> 2").query(Long.class).list();
		assertThat(unbalancedTransfers).as("transfers whose signed entries do not sum to zero").isEmpty();

		List<Long> driftingAccounts = jdbc.sql("SELECT a.id FROM accounts a LEFT JOIN ledger_entries e ON e.account_id = a.id "
				+ "GROUP BY a.id, a.balance HAVING a.balance <> COALESCE(SUM(" + SIGNED + "), 0)").query(Long.class).list();
		assertThat(driftingAccounts).as("accounts whose cached balance differs from their entry sum").isEmpty();

		long total = jdbc.sql("SELECT COALESCE(SUM(" + SIGNED + "), 0) FROM ledger_entries e").query(Long.class).single();
		assertThat(total).as("signed sum of every entry").isZero();

		assertThat(jdbc.sql("SELECT count(*) FROM accounts WHERE balance < 0 AND type <> 'EXTERNAL'").query(Long.class).single())
				.isZero();
		// The EUR/USD transfers above are currency mismatches, so the USD account is untouched.
		assertThat(jdbc.sql("SELECT balance FROM accounts WHERE id = :id").param("id", aliceUsd).query(Long.class).single())
				.isEqualTo(2_000);
	}

}
