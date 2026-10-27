package com.zainali.ledger.account;

import java.util.List;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zainali.ledger.common.Caller;
import com.zainali.ledger.common.Currency;
import com.zainali.ledger.common.NotFoundException;

@Service
class AccountService {

	private final AccountRepository accounts;

	AccountService(AccountRepository accounts) {
		this.accounts = accounts;
	}

	/** The owner defaults to the caller; only an ADMIN may open an account for someone else. */
	@Transactional
	Account create(Caller caller, Currency currency, String requestedOwnerId) {
		String ownerId = requestedOwnerId != null ? requestedOwnerId : caller.subject();
		if (!caller.admin() && !ownerId.equals(caller.subject())) {
			throw new AccessDeniedException("Only an admin can open an account for another owner");
		}
		return accounts.save(Account.openCustomer(ownerId, currency));
	}

	/** Not-visible and not-existing are the same {@link NotFoundException}, so ids cannot be probed. */
	@Transactional(readOnly = true)
	Account get(Caller caller, long id) {
		var account = caller.admin() ? accounts.findById(id) : accounts.findByIdAndOwnerId(id, caller.subject());
		return account.orElseThrow(() -> new NotFoundException("Account not found"));
	}

	@Transactional(readOnly = true)
	List<Account> list(Caller caller) {
		return caller.admin() ? accounts.findAllByOrderByIdAsc() : accounts.findByOwnerIdOrderByIdAsc(caller.subject());
	}

}
