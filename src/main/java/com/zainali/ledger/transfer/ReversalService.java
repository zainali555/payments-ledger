package com.zainali.ledger.transfer;

import java.util.Map;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.zainali.ledger.account.Account;
import com.zainali.ledger.account.AccountLocking;
import com.zainali.ledger.common.Caller;
import com.zainali.ledger.common.ErrorCode;
import com.zainali.ledger.common.LedgerException;
import com.zainali.ledger.common.NotFoundException;
import com.zainali.ledger.idempotency.IdempotencyService;
import com.zainali.ledger.idempotency.IdempotentResult;
import com.zainali.ledger.idempotency.RequestHash;
import com.zainali.ledger.ledger.LedgerService;

/**
 * Reverses a completed transfer with a new REVERSAL transfer that moves the money back (DECISIONS 10). Entries are
 * append-only: the reversal writes two new entries, and the only change to an existing row is the original's status.
 */
@Service
class ReversalService {

	static final String ONE_REVERSAL_PER_TRANSFER = "transfers_reverses_uq";

	private static final String TRANSFER_NOT_FOUND = "Transfer not found";

	private final IdempotencyService idempotency;
	private final AccountLocking accountLocking;
	private final TransferRepository transfers;
	private final LedgerService ledger;

	ReversalService(IdempotencyService idempotency, AccountLocking accountLocking, TransferRepository transfers,
			LedgerService ledger) {
		this.idempotency = idempotency;
		this.accountLocking = accountLocking;
		this.transfers = transfers;
		this.ledger = ledger;
	}

	/** Callers must already be authorised as ADMIN; the filter chain enforces that for the endpoint. */
	@Transactional(isolation = Isolation.READ_COMMITTED) // required by the idempotency and lock reads, DECISIONS 9
	IdempotentResult<TransferResponse> reverse(Caller caller, String idempotencyKey, long transferId) {
		String requestHash = RequestHash.of("REVERSE", transferId);
		return idempotency.execute(caller.subject(), idempotencyKey, requestHash, TransferResponse.class,
				HttpStatus.CREATED.value(), () -> post(caller, transferId));
	}

	private TransferResponse post(Caller caller, long transferId) {
		// Type and accounts never change, so they are read as plain values before any lock. That rejects deposits
		// without locking the hot EXTERNAL row, and keeps every entity out of the persistence context until it is
		// loaded under its lock (DECISIONS 16, lock freshness).
		TransferParties parties = transfers.findPartiesById(transferId)
				.orElseThrow(() -> new NotFoundException(TRANSFER_NOT_FOUND));
		if (parties.type() != TransferType.TRANSFER) {
			throw new LedgerException(ErrorCode.NOT_REVERSIBLE, "Only a plain transfer can be reversed");
		}

		// Global lock order (DECISIONS 8): idempotency key, then accounts by ascending id, then the transfer row. Every
		// reversal of this transfer locks the same two accounts first, so concurrent reversals run one at a time and
		// the second reads the status the first committed.
		Map<Long, Account> locked = accountLocking.lockInIdOrder(parties.sourceAccountId(), parties.targetAccountId());
		Transfer original = transfers.findByIdForUpdate(transferId)
				.orElseThrow(() -> new IllegalStateException("Transfer " + transferId + " disappeared under lock"));
		if (original.getStatus() != TransferStatus.COMPLETED) {
			throw new LedgerException(ErrorCode.ALREADY_REVERSED, "This transfer has already been reversed");
		}

		// The original recipient pays the money back; debit() rejects an overdraft with INSUFFICIENT_FUNDS.
		Account originalSource = locked.get(original.getSourceAccountId());
		Account originalTarget = locked.get(original.getTargetAccountId());
		originalTarget.debit(original.getAmount());
		originalSource.credit(original.getAmount());

		Transfer reversal = saveReversal(Transfer.reversalOf(original, caller.subject()));
		original.markReversed();
		ledger.recordTransfer(reversal.getId(), reversal.getSourceAccountId(), reversal.getTargetAccountId(),
				reversal.getAmount(), reversal.getCurrency());
		return TransferResponse.from(reversal);
	}

	/**
	 * The unique {@code reverses_transfer_id} is the backstop for "once only": the locks above already serialise
	 * reversals, but if they were ever bypassed the database still refuses a second reversal, and it is reported as
	 * ALREADY_REVERSED rather than as an internal error.
	 */
	private Transfer saveReversal(Transfer reversal) {
		try {
			return transfers.save(reversal);
		}
		catch (DataIntegrityViolationException ex) {
			if (violates(ex, ONE_REVERSAL_PER_TRANSFER)) {
				throw new LedgerException(ErrorCode.ALREADY_REVERSED, "This transfer has already been reversed");
			}
			throw ex;
		}
	}

	private static boolean violates(Throwable ex, String constraint) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation
					&& constraint.equalsIgnoreCase(violation.getConstraintName())) {
				return true;
			}
		}
		return false;
	}

}
