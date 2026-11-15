package com.zainali.ledger.reconciliation;

import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only integrity checks (DECISIONS 19). It takes no locks: plain {@code SELECT}s in a read-only
 * {@code REPEATABLE READ} transaction, so both checks see one snapshot even while transfers commit, and it can neither
 * block nor be blocked by them.
 */
@Service
public class ReconciliationService {

	/** Each list is cut here, so a badly damaged ledger cannot produce an unbounded response. */
	static final int MAX_REPORTED = 1000;

	private static final String SIGNED_AMOUNT = "CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END";

	private final JdbcClient jdbc;

	ReconciliationService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public ReconciliationReport reconcile() {
		Instant checkedAt = Instant.now();

		List<TransferImbalance> imbalances = jdbc.sql("""
				SELECT transfer_id, SUM(%s) AS signed_sum
				FROM ledger_entries
				GROUP BY transfer_id
				HAVING SUM(%s) <> 0
				ORDER BY transfer_id
				LIMIT :limit""".formatted(SIGNED_AMOUNT, SIGNED_AMOUNT))
				.param("limit", MAX_REPORTED + 1)
				.query((rs, row) -> new TransferImbalance(rs.getLong("transfer_id"), rs.getLong("signed_sum")))
				.list();

		List<BalanceMismatch> mismatches = jdbc.sql("""
				SELECT a.id, a.balance, COALESCE(e.entries_sum, 0) AS entries_sum
				FROM accounts a
				LEFT JOIN (SELECT account_id, SUM(%s) AS entries_sum
				           FROM ledger_entries GROUP BY account_id) e ON e.account_id = a.id
				WHERE a.balance <> COALESCE(e.entries_sum, 0)
				ORDER BY a.id
				LIMIT :limit""".formatted(SIGNED_AMOUNT))
				.param("limit", MAX_REPORTED + 1)
				.query((rs, row) -> new BalanceMismatch(rs.getLong("id"), rs.getLong("balance"),
						rs.getLong("entries_sum")))
				.list();

		boolean truncated = imbalances.size() > MAX_REPORTED || mismatches.size() > MAX_REPORTED;
		return new ReconciliationReport(checkedAt, cap(imbalances), cap(mismatches), truncated);
	}

	private static <T> List<T> cap(List<T> rows) {
		return rows.size() > MAX_REPORTED ? List.copyOf(rows.subList(0, MAX_REPORTED)) : List.copyOf(rows);
	}

}
