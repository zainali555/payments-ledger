package com.zainali.ledger.account;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.zainali.ledger.common.Currency;
import com.zainali.ledger.common.NotFoundException;

/**
 * What the transfer package needs from accounts: the per-currency EXTERNAL account, and row locks taken in a fixed
 * order so two requests touching the same accounts can never deadlock (DECISIONS 8).
 */
@Service
public class AccountLocking {

	private final AccountRepository accounts;

	AccountLocking(AccountRepository accounts) {
		this.accounts = accounts;
	}

	/** The id of the EXTERNAL account for a currency. Seeded by migration, so it always exists. */
	@Transactional(propagation = Propagation.MANDATORY)
	public long externalAccountId(Currency currency) {
		return accounts.findIdByTypeAndCurrency(AccountType.EXTERNAL, currency)
				.orElseThrow(() -> new IllegalStateException("No EXTERNAL account for " + currency));
	}

	/**
	 * Locks the accounts with {@code SELECT ... FOR UPDATE} in ascending id order and returns them keyed by id, in that
	 * order. Duplicate ids are locked once. A missing account is a {@link NotFoundException}.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Map<Long, Account> lockInIdOrder(long... ids) {
		TreeSet<Long> ordered = new TreeSet<>();
		for (long id : ids) {
			ordered.add(id);
		}
		Map<Long, Account> locked = new LinkedHashMap<>();
		for (long id : ordered) {
			locked.put(id, accounts.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Account not found")));
		}
		return locked;
	}

}
