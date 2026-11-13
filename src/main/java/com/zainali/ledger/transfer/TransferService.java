package com.zainali.ledger.transfer;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.zainali.ledger.account.Account;
import com.zainali.ledger.account.AccountLocking;
import com.zainali.ledger.account.AccountType;
import com.zainali.ledger.common.Caller;
import com.zainali.ledger.common.Currency;
import com.zainali.ledger.common.ErrorCode;
import com.zainali.ledger.common.LedgerException;
import com.zainali.ledger.common.NotFoundException;
import com.zainali.ledger.idempotency.IdempotencyService;
import com.zainali.ledger.idempotency.IdempotentResult;
import com.zainali.ledger.idempotency.RequestHash;
import com.zainali.ledger.ledger.LedgerService;

@Service
class TransferService {

	private static final String ACCOUNT_NOT_FOUND = "Account not found";
	private static final String TRANSFER_NOT_FOUND = "Transfer not found";

	private final IdempotencyService idempotency;
	private final AccountLocking accountLocking;
	private final TransferRepository transfers;
	private final LedgerService ledger;

	TransferService(IdempotencyService idempotency, AccountLocking accountLocking, TransferRepository transfers,
			LedgerService ledger) {
		this.idempotency = idempotency;
		this.accountLocking = accountLocking;
		this.transfers = transfers;
		this.ledger = ledger;
	}

	@Transactional(isolation = Isolation.READ_COMMITTED) // required by the idempotency and lock reads, DECISIONS 9
	IdempotentResult<TransferResponse> transfer(Caller caller, String idempotencyKey, long sourceAccountId,
			long targetAccountId, long amount, Currency currency) {
		String requestHash = RequestHash.of("TRANSFER", sourceAccountId, targetAccountId, amount, currency);
		return idempotency.execute(caller.subject(), idempotencyKey, requestHash, TransferResponse.class,
				HttpStatus.CREATED.value(), () -> post(caller, sourceAccountId, targetAccountId, amount, currency));
	}

	/** A party to the transfer (owner of either account) or an ADMIN. Anyone else gets the same 404 as for a missing id. */
	@Transactional(readOnly = true)
	TransferResponse get(Caller caller, long id) {
		Transfer transfer = transfers.findById(id).orElseThrow(() -> new NotFoundException(TRANSFER_NOT_FOUND));
		if (!caller.admin() && !isOwner(caller, transfer.getSourceAccountId())
				&& !isOwner(caller, transfer.getTargetAccountId())) {
			throw new NotFoundException(TRANSFER_NOT_FOUND);
		}
		return TransferResponse.from(transfer);
	}

	private TransferResponse post(Caller caller, long sourceId, long targetId, long amount, Currency currency) {
		// Ownership first, and as a bare owner id: no Account entity may be loaded before the row lock below, or the
		// funds check could run against a stale balance. It also comes before SAME_ACCOUNT so that rule cannot be used
		// to probe accounts the caller cannot see.
		if (!caller.admin() && !isOwner(caller, sourceId)) {
			throw new NotFoundException(ACCOUNT_NOT_FOUND);
		}
		if (sourceId == targetId) {
			throw new LedgerException(ErrorCode.SAME_ACCOUNT, "Source and target must be different accounts");
		}

		Map<Long, Account> locked = accountLocking.lockInIdOrder(sourceId, targetId);
		Account source = locked.get(sourceId);
		Account target = locked.get(targetId);

		if (source.getType() == AccountType.EXTERNAL || target.getType() == AccountType.EXTERNAL) {
			throw new LedgerException(ErrorCode.EXTERNAL_ACCOUNT_NOT_ALLOWED,
					"EXTERNAL accounts cannot be used in transfers");
		}
		if (source.getCurrency() != currency || target.getCurrency() != currency) {
			throw new LedgerException(ErrorCode.CURRENCY_MISMATCH,
					"The transfer currency must match the currency of both accounts");
		}

		// Both balances were read by the FOR UPDATE load above, so they are current. debit() rejects an overdraft.
		source.debit(amount);
		target.credit(amount);
		Transfer transfer = transfers.save(
				Transfer.completed(TransferType.TRANSFER, sourceId, targetId, amount, currency, caller.subject()));
		ledger.recordTransfer(transfer.getId(), sourceId, targetId, amount, currency);
		return TransferResponse.from(transfer);
	}

	private boolean isOwner(Caller caller, long accountId) {
		return accountLocking.ownerIdOf(accountId).filter(caller.subject()::equals).isPresent();
	}

}
