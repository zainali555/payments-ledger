package com.zainali.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.zainali.ledger.common.Currency;

class LedgerServiceTests {

	private final LedgerEntryRepository repository = mock(LedgerEntryRepository.class);
	private final LedgerService service = new LedgerService(repository);

	@Test
	void recordsOneDebitAndOneCreditOfTheSameAmount() {
		service.recordTransfer(42, 10, 20, 1_500, Currency.USD);

		ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
		verify(repository, times(2)).save(captor.capture());
		List<LedgerEntry> entries = captor.getAllValues();

		LedgerEntry debit = entries.stream().filter(e -> e.getDirection() == EntryDirection.DEBIT).findFirst().orElseThrow();
		LedgerEntry credit = entries.stream().filter(e -> e.getDirection() == EntryDirection.CREDIT).findFirst().orElseThrow();
		assertThat(debit.getAccountId()).isEqualTo(10);
		assertThat(credit.getAccountId()).isEqualTo(20);
		assertThat(entries).allSatisfy(entry -> {
			assertThat(entry.getTransferId()).isEqualTo(42);
			assertThat(entry.getAmount()).isEqualTo(1_500);
			assertThat(entry.getCurrency()).isEqualTo(Currency.USD);
		});
	}

	@Test
	void signedEntriesSumToZero() {
		service.recordTransfer(1, 10, 20, 999, Currency.EUR);

		ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
		verify(repository, times(2)).save(captor.capture());

		long signedSum = captor.getAllValues().stream()
				.mapToLong(e -> e.getDirection() == EntryDirection.CREDIT ? e.getAmount() : -e.getAmount())
				.sum();
		assertThat(signedSum).isZero();
	}

}
