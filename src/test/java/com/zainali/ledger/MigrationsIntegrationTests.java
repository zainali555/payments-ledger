package com.zainali.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class MigrationsIntegrationTests {

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void allMigrationsApplied() {
		List<Map<String, Object>> history = jdbc.queryForList(
				"SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");

		assertThat(history).hasSize(5);
		assertThat(history).allSatisfy(row -> assertThat(row.get("success")).isEqualTo(true));
	}

	@Test
	void tablesExist() {
		List<String> tables = jdbc.queryForList(
				"SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);

		assertThat(tables).contains("accounts", "transfers", "ledger_entries", "idempotency_keys");
	}

	@Test
	void seedsOneExternalAccountPerSupportedCurrency() {
		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT currency, balance, owner_id FROM accounts WHERE type = 'EXTERNAL' ORDER BY currency");

		assertThat(rows).hasSize(2);
		assertThat(rows.get(0)).containsEntry("currency", "EUR").containsEntry("balance", 0L);
		assertThat(rows.get(1)).containsEntry("currency", "USD").containsEntry("balance", 0L);
		assertThat(rows).allSatisfy(row -> assertThat(row.get("owner_id")).isNull());
	}

	@Test
	void rejectsSecondExternalAccountForCurrency() {
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO accounts (owner_id, type, currency) VALUES (NULL, 'EXTERNAL', 'EUR')"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void rejectsUnsupportedCurrency() {
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO accounts (owner_id, type, currency) VALUES ('u1', 'CUSTOMER', 'GBP')"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void rejectsNegativeBalanceOnCustomerAccountButAllowsItOnExternal() {
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO accounts (owner_id, type, currency, balance) VALUES ('u1', 'CUSTOMER', 'EUR', -1)"))
				.isInstanceOf(DataIntegrityViolationException.class);

		int updated = jdbc.update("UPDATE accounts SET balance = -500 WHERE type = 'EXTERNAL' AND currency = 'EUR'");
		assertThat(updated).isEqualTo(1);
		jdbc.update("UPDATE accounts SET balance = 0 WHERE type = 'EXTERNAL' AND currency = 'EUR'");
	}

	@Test
	void customerAccountRequiresOwner() {
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO accounts (owner_id, type, currency) VALUES (NULL, 'CUSTOMER', 'EUR')"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void ledgerEntryRequiresPositiveAmount() {
		long[] ids = createTransfer();

		assertThatThrownBy(() -> insertEntry(ids[2], ids[0], "DEBIT", 0, "EUR"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertEntry(ids[2], ids[0], "DEBIT", -5, "EUR"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void ledgerEntryMustMatchAccountCurrency() {
		long[] ids = createTransfer();

		assertThatThrownBy(() -> insertEntry(ids[2], ids[0], "DEBIT", 10, "USD"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void ledgerEntryRequiresExistingAccountAndTransfer() {
		long[] ids = createTransfer();

		assertThatThrownBy(() -> insertEntry(ids[2], 999_999L, "DEBIT", 10, "EUR"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertEntry(999_999L, ids[0], "DEBIT", 10, "EUR"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void ledgerEntriesAreAppendOnly() {
		long[] ids = createTransfer();
		insertEntry(ids[2], ids[0], "DEBIT", 10, "EUR");

		assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entries SET amount = 11 WHERE transfer_id = ?", ids[2]))
				.hasMessageContaining("append-only");
		assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entries WHERE transfer_id = ?", ids[2]))
				.hasMessageContaining("append-only");
		assertThatThrownBy(() -> jdbc.execute("TRUNCATE ledger_entries"))
				.hasMessageContaining("append-only");
	}

	@Test
	void transferRejectsSameAccountAndNonPositiveAmount() {
		long a = createAccount("EUR");

		assertThatThrownBy(() -> insertTransfer(a, a, 10, "EUR"))
				.isInstanceOf(DataIntegrityViolationException.class);
		long b = createAccount("EUR");
		assertThatThrownBy(() -> insertTransfer(a, b, 0, "EUR"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void transferCurrencyMustMatchBothAccounts() {
		long eur = createAccount("EUR");
		long usd = createAccount("USD");

		assertThatThrownBy(() -> insertTransfer(eur, usd, 10, "EUR"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void transferCanBeReversedOnlyOnce() {
		long[] ids = createTransfer();
		insertReversal(ids[0], ids[1], ids[2]);

		assertThatThrownBy(() -> insertReversal(ids[0], ids[1], ids[2]))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void reversalTypeAndLinkMustAgree() {
		long[] ids = createTransfer();

		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO transfers (type, status, source_account_id, target_account_id, amount, currency,
				                       reverses_transfer_id, created_by)
				VALUES ('REVERSAL', 'COMPLETED', ?, ?, 10, 'EUR', NULL, 'u1')""", ids[1], ids[0]))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO transfers (type, status, source_account_id, target_account_id, amount, currency,
				                       reverses_transfer_id, created_by)
				VALUES ('TRANSFER', 'COMPLETED', ?, ?, 10, 'EUR', ?, 'u1')""", ids[0], ids[1], ids[2]))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void idempotencyKeyIsUniquePerOwner() {
		insertKey("owner-a", "key-1");

		assertThatThrownBy(() -> insertKey("owner-a", "key-1"))
				.isInstanceOf(DataIntegrityViolationException.class);
		insertKey("owner-b", "key-1");
	}

	@Test
	void idempotencyKeyOnConflictDoNothingReportsZeroRows() {
		insertKey("owner-c", "key-1");

		int inserted = jdbc.update("""
				INSERT INTO idempotency_keys (owner_id, key, request_hash) VALUES ('owner-c', 'key-1', ?)
				ON CONFLICT (owner_id, key) DO NOTHING""", "0".repeat(64));

		assertThat(inserted).isZero();
	}

	@Test
	void idempotencyResponseColumnsAreSetTogether() {
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO idempotency_keys (owner_id, key, request_hash, response_status)
				VALUES ('owner-d', 'key-1', ?, 200)""", "0".repeat(64)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	// Inserts two EUR customer accounts and a transfer between them; returns {source, target, transfer}.
	private long[] createTransfer() {
		long source = createAccount("EUR");
		long target = createAccount("EUR");
		return new long[] { source, target, insertTransfer(source, target, 10, "EUR") };
	}

	private long createAccount(String currency) {
		Long id = jdbc.queryForObject(
				"INSERT INTO accounts (owner_id, type, currency) VALUES ('user-1', 'CUSTOMER', ?) RETURNING id",
				Long.class, currency);
		return id;
	}

	private long insertTransfer(long source, long target, long amount, String currency) {
		Long id = jdbc.queryForObject("""
				INSERT INTO transfers (type, status, source_account_id, target_account_id, amount, currency, created_by)
				VALUES ('TRANSFER', 'COMPLETED', ?, ?, ?, ?, 'user-1') RETURNING id""",
				Long.class, source, target, amount, currency);
		return id;
	}

	private void insertReversal(long source, long target, long reverses) {
		jdbc.update("""
				INSERT INTO transfers (type, status, source_account_id, target_account_id, amount, currency,
				                       reverses_transfer_id, created_by)
				VALUES ('REVERSAL', 'COMPLETED', ?, ?, 10, 'EUR', ?, 'admin-1')""", target, source, reverses);
	}

	private void insertEntry(long transferId, long accountId, String direction, long amount, String currency) {
		jdbc.update("""
				INSERT INTO ledger_entries (transfer_id, account_id, direction, amount, currency)
				VALUES (?, ?, ?, ?, ?)""", transferId, accountId, direction, amount, currency);
	}

	private void insertKey(String owner, String key) {
		jdbc.update("INSERT INTO idempotency_keys (owner_id, key, request_hash) VALUES (?, ?, ?)",
				owner, key, "0".repeat(64));
	}

}
