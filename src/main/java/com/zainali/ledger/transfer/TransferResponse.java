package com.zainali.ledger.transfer;

import java.time.Instant;

import com.zainali.ledger.common.Currency;

/** {@code amount} is in minor units (cents). */
record TransferResponse(
		long id,
		TransferType type,
		TransferStatus status,
		long sourceAccountId,
		long targetAccountId,
		long amount,
		Currency currency,
		Instant createdAt) {

	static TransferResponse from(Transfer transfer) {
		return new TransferResponse(transfer.getId(), transfer.getType(), transfer.getStatus(),
				transfer.getSourceAccountId(), transfer.getTargetAccountId(), transfer.getAmount(), transfer.getCurrency(),
				transfer.getCreatedAt());
	}

}
