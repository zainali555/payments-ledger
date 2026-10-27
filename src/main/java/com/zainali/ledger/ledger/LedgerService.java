package com.zainali.ledger.ledger;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.zainali.ledger.common.Currency;

@Service
public class LedgerService {

	private final LedgerEntryRepository entries;

	LedgerService(LedgerEntryRepository entries) {
		this.entries = entries;
	}

	/**
	 * Writes the two sides of a transfer: a debit and a credit of the same amount, so the signed entries sum to zero.
	 * Runs inside the caller's transaction, which also updates the balances and the transfer row.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void recordTransfer(long transferId, long debitAccountId, long creditAccountId, long amount, Currency currency) {
		entries.save(LedgerEntry.of(EntryDirection.DEBIT, transferId, debitAccountId, amount, currency));
		entries.save(LedgerEntry.of(EntryDirection.CREDIT, transferId, creditAccountId, amount, currency));
	}

}
