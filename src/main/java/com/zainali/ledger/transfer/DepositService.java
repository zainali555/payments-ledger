package com.zainali.ledger.transfer;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zainali.ledger.account.Account;
import com.zainali.ledger.account.AccountLocking;
import com.zainali.ledger.account.AccountType;
import com.zainali.ledger.common.Caller;
import com.zainali.ledger.common.Currency;
import com.zainali.ledger.common.ErrorCode;
import com.zainali.ledger.common.LedgerException;
import com.zainali.ledger.idempotency.IdempotencyService;
import com.zainali.ledger.idempotency.IdempotentResult;
import com.zainali.ledger.idempotency.RequestHash;
import com.zainali.ledger.ledger.LedgerService;

@Service
class DepositService {

	private final IdempotencyService idempotency;
	private final AccountLocking accountLocking;
	private final TransferRepository transfers;
	private final LedgerService ledger;

	DepositService(IdempotencyService idempotency, AccountLocking accountLocking, TransferRepository transfers,
			LedgerService ledger) {
		this.idempotency = idempotency;
		this.accountLocking = accountLocking;
		this.transfers = transfers;
		this.ledger = ledger;
	}

	/** Callers must already be authorised as ADMIN; the filter chain enforces that for the endpoint. */
	@Transactional
	IdempotentResult<TransferResponse> deposit(Caller caller, String idempotencyKey, long accountId, long amount,
			Currency currency) {
		String requestHash = RequestHash.of("DEPOSIT", accountId, amount, currency);
		return idempotency.execute(caller.subject(), idempotencyKey, requestHash, TransferResponse.class,
				HttpStatus.CREATED.value(), () -> post(caller, accountId, amount, currency));
	}

	/**
	 * Moves money from the currency's EXTERNAL account to the target. Every deposit in a currency locks that one
	 * EXTERNAL row, so deposits in the same currency run one at a time (DECISIONS 16).
	 */
	private TransferResponse post(Caller caller, long accountId, long amount, Currency currency) {
		long externalId = accountLocking.externalAccountId(currency);
		Map<Long, Account> locked = accountLocking.lockInIdOrder(accountId, externalId);
		Account target = locked.get(accountId);
		Account external = locked.get(externalId);

		if (target.getType() == AccountType.EXTERNAL) {
			throw new LedgerException(ErrorCode.EXTERNAL_ACCOUNT_NOT_ALLOWED, "Cannot deposit into an EXTERNAL account");
		}
		if (target.getCurrency() != currency) {
			throw new LedgerException(ErrorCode.CURRENCY_MISMATCH,
					"Account " + accountId + " holds " + target.getCurrency() + ", not " + currency);
		}

		external.debit(amount);
		target.credit(amount);
		Transfer transfer = transfers.save(
				Transfer.completed(TransferType.DEPOSIT, externalId, accountId, amount, currency, caller.subject()));
		ledger.recordTransfer(transfer.getId(), externalId, accountId, amount, currency);
		return TransferResponse.from(transfer);
	}

}
