package com.zainali.ledger.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the reconciliation on a schedule and logs the outcome. Set {@code ledger.reconciliation.enabled=false} to turn
 * it off, and {@code ledger.reconciliation.cron} to change the schedule (default: hourly).
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "ledger.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
class ReconciliationJob {

	private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

	private final ReconciliationService service;

	ReconciliationJob(ReconciliationService service) {
		this.service = service;
	}

	@Scheduled(cron = "${ledger.reconciliation.cron:0 0 * * * *}")
	void run() {
		try {
			ReconciliationReport report = service.reconcile();
			if (report.consistent()) {
				log.info("Reconciliation passed");
			}
			else {
				log.error("Reconciliation found {} transfer imbalance(s) and {} balance mismatch(es){}: {}",
						report.transferImbalances().size(), report.balanceMismatches().size(),
						report.truncated() ? " (truncated)" : "", report);
			}
		}
		catch (RuntimeException ex) {
			// a failed run must not stop the schedule
			log.error("Reconciliation could not run", ex);
		}
	}

}
