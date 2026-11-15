package com.zainali.ledger.ledger;

import java.time.Instant;

import com.zainali.ledger.common.Currency;

/** One ledger entry as the API shows it. {@code amount} is positive, in minor units; {@code direction} says which way. */
record EntryResponse(long id, long transferId, EntryDirection direction, long amount, Currency currency, Instant createdAt) {

	static EntryResponse from(LedgerEntry entry) {
		return new EntryResponse(entry.getId(), entry.getTransferId(), entry.getDirection(), entry.getAmount(),
				entry.getCurrency(), entry.getCreatedAt());
	}

}
