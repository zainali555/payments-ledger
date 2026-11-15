package com.zainali.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.zainali.ledger.TestcontainersConfiguration;

/**
 * POST /admin/reconciliation against a real Postgres (DECISIONS 19).
 *
 * The corruption tests commit real damage, because the check runs on its own connection and cannot see uncommitted
 * rows. Each one repairs the damage in a {@code finally} block so the shared database stays clean for other tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ReconciliationApiIntegrationTests {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PlatformTransactionManager transactionManager;

	// --- helpers ---

	private static String newId(String prefix) {
		return prefix + "-" + UUID.randomUUID();
	}

	private static RequestPostProcessor user(String subject) {
		return jwt().jwt(jwt -> jwt.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private static RequestPostProcessor admin() {
		return jwt().jwt(jwt -> jwt.subject(newId("admin"))).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
	}

	private static long idOf(String json) {
		Matcher matcher = Pattern.compile("\"id\":(\\d+)").matcher(json);
		assertThat(matcher.find()).isTrue();
		return Long.parseLong(matcher.group(1));
	}

	private long createAccount(String owner) throws Exception {
		String body = mockMvc.perform(post("/accounts").with(user(owner)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"currency\":\"EUR\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return idOf(body);
	}

	private void deposit(long account, long amount) throws Exception {
		mockMvc.perform(post("/accounts/{id}/deposits", account).with(admin()).header("Idempotency-Key", newId("fund"))
				.contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + amount + ",\"currency\":\"EUR\"}"))
				.andExpect(status().isCreated());
	}

	private long transfer(String owner, long source, long target, long amount) throws Exception {
		String body = mockMvc.perform(post("/transfers").with(user(owner)).header("Idempotency-Key", newId("key"))
				.contentType(MediaType.APPLICATION_JSON).content("{\"sourceAccountId\":" + source + ",\"targetAccountId\":"
						+ target + ",\"amount\":" + amount + ",\"currency\":\"EUR\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return idOf(body);
	}

	private void reverse(long transferId) throws Exception {
		mockMvc.perform(post("/transfers/{id}/reverse", transferId).with(admin()).header("Idempotency-Key", newId("rev")))
				.andExpect(status().isCreated());
	}

	private ResultActions reconcile() throws Exception {
		return mockMvc.perform(post("/admin/reconciliation").with(admin()));
	}

	private long entryId(long transferId, String direction) {
		return jdbc.sql("SELECT id FROM ledger_entries WHERE transfer_id = :t AND direction = :d")
				.param("t", transferId).param("d", direction).query(Long.class).single();
	}

	private long entryAmount(long entryId) {
		return jdbc.sql("SELECT amount FROM ledger_entries WHERE id = :id").param("id", entryId).query(Long.class)
				.single();
	}

	/**
	 * Runs {@code work} in one transaction with {@code session_replication_role = replica}. {@code SET LOCAL} ends with
	 * the transaction, so the append-only triggers are bypassed for this transaction only and are enforced again after
	 * the commit.
	 */
	private void withTriggersDisabled(Runnable work) {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			jdbc.sql("SET LOCAL session_replication_role = replica").update();
			work.run();
		});
	}

	// --- tests ---

	@Test
	void cleanDatabaseGivesEmptyReport() throws Exception {
		String aliceSub = newId("alice");
		long alice = createAccount(aliceSub);
		long bob = createAccount(newId("bob"));
		deposit(alice, 1_000);
		deposit(bob, 500);
		long sent = transfer(aliceSub, alice, bob, 300);
		reverse(sent);
		transfer(aliceSub, alice, bob, 50);

		reconcile().andExpect(status().isOk())
				.andExpect(jsonPath("$.consistent", is(true)))
				.andExpect(jsonPath("$.truncated", is(false)))
				.andExpect(jsonPath("$.transferImbalances", hasSize(0)))
				.andExpect(jsonPath("$.balanceMismatches", hasSize(0)))
				.andExpect(jsonPath("$.checkedAt").isNotEmpty());
	}

	@Test
	void emptyLedgerGivesEmptyReport() throws Exception {
		// accounts with no entries at all (balance 0, no entries) are consistent
		createAccount(newId("idle"));

		reconcile().andExpect(status().isOk())
				.andExpect(jsonPath("$.consistent", is(true)))
				.andExpect(jsonPath("$.balanceMismatches", hasSize(0)));
	}

	@Test
	void rejectsAnonymousAndNonAdminCallers() throws Exception {
		mockMvc.perform(post("/admin/reconciliation")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/admin/reconciliation").with(user(newId("alice")))).andExpect(status().isForbidden());
	}

	@Test
	void detectsCachedBalanceThatDiffersFromItsEntries() throws Exception {
		String aliceSub = newId("alice");
		long alice = createAccount(aliceSub);
		long bob = createAccount(newId("bob"));
		deposit(alice, 1_000);
		transfer(aliceSub, alice, bob, 400);

		jdbc.sql("UPDATE accounts SET balance = balance + 7 WHERE id = :id").param("id", alice).update();
		try {
			reconcile().andExpect(status().isOk())
					.andExpect(jsonPath("$.consistent", is(false)))
					.andExpect(jsonPath("$.transferImbalances", hasSize(0)))
					.andExpect(jsonPath("$.balanceMismatches", hasSize(1)))
					.andExpect(jsonPath("$.balanceMismatches[0].accountId", is((int) alice)))
					.andExpect(jsonPath("$.balanceMismatches[0].cachedBalance", is(607)))
					.andExpect(jsonPath("$.balanceMismatches[0].entriesSum", is(600)));
		}
		finally {
			jdbc.sql("UPDATE accounts SET balance = balance - 7 WHERE id = :id").param("id", alice).update();
		}
		reconcile().andExpect(jsonPath("$.consistent", is(true)));
	}

	@Test
	void detectsAMissingEntry() throws Exception {
		String aliceSub = newId("alice");
		long alice = createAccount(aliceSub);
		long bob = createAccount(newId("bob"));
		deposit(alice, 1_000);
		long transferId = transfer(aliceSub, alice, bob, 400);
		long creditId = entryId(transferId, "CREDIT");

		// the credit to bob is lost: the transfer no longer nets to zero, and bob's cached 400 has no entry behind it
		withTriggersDisabled(() -> jdbc.sql("DELETE FROM ledger_entries WHERE id = :id").param("id", creditId).update());
		try {
			reconcile().andExpect(status().isOk())
					.andExpect(jsonPath("$.consistent", is(false)))
					.andExpect(jsonPath("$.transferImbalances[?(@.transferId == " + transferId + ")].signedSum",
							hasItem(-400)))
					.andExpect(jsonPath("$.balanceMismatches[?(@.accountId == " + bob + ")].cachedBalance",
							hasItem(400)))
					.andExpect(jsonPath("$.balanceMismatches[?(@.accountId == " + bob + ")].entriesSum", hasItem(0)));
		}
		finally {
			withTriggersDisabled(() -> jdbc.sql("""
					INSERT INTO ledger_entries (id, transfer_id, account_id, direction, amount, currency)
					OVERRIDING SYSTEM VALUE VALUES (:id, :t, :a, 'CREDIT', 400, 'EUR')""")
					.param("id", creditId).param("t", transferId).param("a", bob).update());
		}
		reconcile().andExpect(jsonPath("$.consistent", is(true)));
	}

	@Test
	void detectsATamperedEntryAmount() throws Exception {
		String aliceSub = newId("alice");
		long alice = createAccount(aliceSub);
		long bob = createAccount(newId("bob"));
		deposit(alice, 1_000);
		long transferId = transfer(aliceSub, alice, bob, 400);
		long debitId = entryId(transferId, "DEBIT");

		withTriggersDisabled(
				() -> jdbc.sql("UPDATE ledger_entries SET amount = 250 WHERE id = :id").param("id", debitId).update());
		try {
			reconcile().andExpect(status().isOk())
					.andExpect(jsonPath("$.consistent", is(false)))
					// debit 250, credit 400: signed sum +150
					.andExpect(jsonPath("$.transferImbalances[?(@.transferId == " + transferId + ")].signedSum",
							hasItem(150)))
					// alice's balance is 600 but her entries now sum to 750
					.andExpect(jsonPath("$.balanceMismatches[?(@.accountId == " + alice + ")].cachedBalance",
							hasItem(600)))
					.andExpect(jsonPath("$.balanceMismatches[?(@.accountId == " + alice + ")].entriesSum",
							hasItem(750)));
		}
		finally {
			withTriggersDisabled(() -> jdbc.sql("UPDATE ledger_entries SET amount = 400 WHERE id = :id")
					.param("id", debitId).update());
		}
		assertThat(entryAmount(debitId)).isEqualTo(400);
		reconcile().andExpect(jsonPath("$.consistent", is(true)));
	}

	@Test
	void appendOnlyTriggersAreEnforcedAgainAfterTheyWereDisabledForOneTransaction() throws Exception {
		String aliceSub = newId("alice");
		long alice = createAccount(aliceSub);
		long bob = createAccount(newId("bob"));
		deposit(alice, 100);
		long debitId = entryId(transfer(aliceSub, alice, bob, 10), "DEBIT");

		withTriggersDisabled(() -> jdbc.sql("SELECT 1").query(Integer.class).single());

		assertThatThrownBy(
				() -> jdbc.sql("UPDATE ledger_entries SET amount = 1 WHERE id = :id").param("id", debitId).update())
				.hasMessageContaining("append-only");
		assertThatThrownBy(() -> jdbc.sql("DELETE FROM ledger_entries WHERE id = :id").param("id", debitId).update())
				.hasMessageContaining("append-only");
	}

	@Test
	void reconciliationDoesNotWaitForLocksHeldOnAccounts() throws Exception {
		String aliceSub = newId("alice");
		long alice = createAccount(aliceSub);
		deposit(alice, 100);

		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
				jdbc.sql("SELECT id FROM accounts WHERE id = :id FOR UPDATE").param("id", alice).query(Long.class)
						.single();
				// the row lock is held here; a check that locked accounts would block until the timeout
				Future<?> check = executor.submit(() -> {
					try {
						reconcile().andExpect(status().isOk()).andExpect(jsonPath("$.consistent", is(true)));
					}
					catch (Exception ex) {
						throw new IllegalStateException(ex);
					}
				});
				try {
					check.get(15, TimeUnit.SECONDS);
				}
				catch (Exception ex) {
					throw new AssertionError("reconciliation did not finish while an account row was locked", ex);
				}
			});
		}
		finally {
			executor.shutdownNow();
		}
	}

}
