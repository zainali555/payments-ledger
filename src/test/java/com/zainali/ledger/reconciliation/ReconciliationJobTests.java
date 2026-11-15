package com.zainali.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class ReconciliationJobTests {

	private final ReconciliationService service = mock(ReconciliationService.class);

	private final ReconciliationJob job = new ReconciliationJob(service);

	@Test
	void runsOneReconciliationPerTick() {
		when(service.reconcile()).thenReturn(new ReconciliationReport(Instant.now(), List.of(), List.of(), false));

		job.run();

		verify(service, times(1)).reconcile();
	}

	@Test
	void aMismatchDoesNotMakeTheScheduledTaskThrow() {
		var report = new ReconciliationReport(Instant.now(), List.of(new TransferImbalance(1, 5)), List.of(), false);
		when(service.reconcile()).thenReturn(report);

		assertThatCode(job::run).doesNotThrowAnyException();
		assertThat(report.consistent()).isFalse();
	}

	@Test
	void aFailingCheckDoesNotMakeTheScheduledTaskThrow() {
		when(service.reconcile()).thenThrow(new IllegalStateException("database down"));

		assertThatCode(job::run).doesNotThrowAnyException();
	}

}
