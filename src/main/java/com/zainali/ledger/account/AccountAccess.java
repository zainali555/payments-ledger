package com.zainali.ledger.account;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zainali.ledger.common.Caller;
import com.zainali.ledger.common.NotFoundException;

/**
 * The visibility rule for accounts, for other packages: a USER sees only accounts they own, an ADMIN sees all. The
 * same rule as {@code GET /accounts/{id}}, so a missing and a hidden account are the same {@link NotFoundException}.
 */
@Service
public class AccountAccess {

	private final AccountRepository accounts;

	AccountAccess(AccountRepository accounts) {
		this.accounts = accounts;
	}

	@Transactional(readOnly = true)
	public void requireVisible(Caller caller, long accountId) {
		boolean visible = caller.admin() ? accounts.existsById(accountId)
				: accounts.existsByIdAndOwnerId(accountId, caller.subject());
		if (!visible) {
			throw new NotFoundException("Account not found");
		}
	}

}
