package com.zainali.ledger.account;

import java.time.Instant;

import com.zainali.ledger.common.Currency;

/** {@code balance} is in minor units (cents). */
record AccountResponse(
		long id,
		String ownerId,
		AccountType type,
		Currency currency,
		long balance,
		Instant createdAt) {

	static AccountResponse from(Account account) {
		return new AccountResponse(account.getId(), account.getOwnerId(), account.getType(), account.getCurrency(),
				account.getBalance(), account.getCreatedAt());
	}

}
