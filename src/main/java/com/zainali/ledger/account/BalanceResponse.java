package com.zainali.ledger.account;

import com.zainali.ledger.common.Currency;

/** The cached balance in minor units; the ledger entries are the source of truth and reconciliation checks they agree. */
record BalanceResponse(long accountId, Currency currency, long balance) {

	static BalanceResponse from(Account account) {
		return new BalanceResponse(account.getId(), account.getCurrency(), account.getBalance());
	}

}
