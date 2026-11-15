package com.zainali.ledger.reconciliation;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Result of one reconciliation run. {@code truncated} is true when a list was cut at
 * {@link ReconciliationService#MAX_REPORTED} entries, so there are more mismatches than shown.
 */
public record ReconciliationReport(Instant checkedAt, List<TransferImbalance> transferImbalances,
		List<BalanceMismatch> balanceMismatches, boolean truncated) {

	@JsonProperty("consistent")
	public boolean consistent() {
		return transferImbalances.isEmpty() && balanceMismatches.isEmpty();
	}

}
