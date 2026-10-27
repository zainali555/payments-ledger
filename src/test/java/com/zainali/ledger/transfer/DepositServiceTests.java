package com.zainali.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

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
import com.zainali.ledger.ledger.LedgerService;

class DepositServiceTests {

	private static final Caller ADMIN = new Caller("admin", true);
	private static final long EXTERNAL_ID = 1;
	private static final long TARGET_ID = 50;

	private final IdempotencyService idempotency = mock(IdempotencyService.class);
	private final AccountLocking locking = mock(AccountLocking.class);
	private final TransferRepository transfers = mock(TransferRepository.class);
	private final LedgerService ledger = mock(LedgerService.class);
	private final DepositService service = new DepositService(idempotency, locking, transfers, ledger);

	@BeforeEach
	@SuppressWarnings("unchecked")
	void runIdempotentWorkInline() {
		when(idempotency.execute(anyString(), anyString(), anyString(), any(), anyInt(), any()))
				.thenAnswer(inv -> new IdempotentResult<>(inv.getArgument(4), ((Supplier<Object>) inv.getArgument(5)).get(), false));
		when(locking.externalAccountId(Currency.EUR)).thenReturn(EXTERNAL_ID);
		when(transfers.save(any(Transfer.class))).thenAnswer(inv -> {
			Transfer transfer = inv.getArgument(0);
			ReflectionTestUtils.setField(transfer, "id", 900L);
			return transfer;
		});
	}

	private static Account account(long id, AccountType type, Currency currency, long balance) {
		// Account's factory is package-private, and this test lives in another package.
		Account account = BeanUtils.instantiateClass(Account.class);
		ReflectionTestUtils.setField(account, "id", id);
		ReflectionTestUtils.setField(account, "currency", currency);
		ReflectionTestUtils.setField(account, "type", type);
		ReflectionTestUtils.setField(account, "balance", balance);
		return account;
	}

	private void lockReturns(Account... accounts) {
		Map<Long, Account> locked = new LinkedHashMap<>();
		for (Account account : accounts) {
			locked.put(account.getId(), account);
		}
		when(locking.lockInIdOrder(anyLong(), anyLong())).thenReturn(locked);
	}

	@Test
	void depositMovesMoneyFromExternalToTarget() {
		Account external = account(EXTERNAL_ID, AccountType.EXTERNAL, Currency.EUR, 0);
		Account target = account(TARGET_ID, AccountType.CUSTOMER, Currency.EUR, 100);
		lockReturns(external, target);

		IdempotentResult<TransferResponse> result = service.deposit(ADMIN, "key", TARGET_ID, 2_500, Currency.EUR);

		assertThat(external.getBalance()).isEqualTo(-2_500);
		assertThat(target.getBalance()).isEqualTo(2_600);
		assertThat(result.status()).isEqualTo(201);
		assertThat(result.replayed()).isFalse();
		assertThat(result.body()).satisfies(body -> {
			assertThat(body.id()).isEqualTo(900);
			assertThat(body.type()).isEqualTo(TransferType.DEPOSIT);
			assertThat(body.status()).isEqualTo(TransferStatus.COMPLETED);
			assertThat(body.sourceAccountId()).isEqualTo(EXTERNAL_ID);
			assertThat(body.targetAccountId()).isEqualTo(TARGET_ID);
			assertThat(body.amount()).isEqualTo(2_500);
			assertThat(body.currency()).isEqualTo(Currency.EUR);
		});
	}

	@Test
	void depositRecordsTheTransferWithTheCallerAsCreator() {
		lockReturns(account(EXTERNAL_ID, AccountType.EXTERNAL, Currency.EUR, 0),
				account(TARGET_ID, AccountType.CUSTOMER, Currency.EUR, 0));

		service.deposit(ADMIN, "key", TARGET_ID, 10, Currency.EUR);

		ArgumentCaptor<Transfer> saved = ArgumentCaptor.forClass(Transfer.class);
		verify(transfers).save(saved.capture());
		assertThat(saved.getValue().getCreatedBy()).isEqualTo("admin");
		assertThat(saved.getValue().getType()).isEqualTo(TransferType.DEPOSIT);
	}

	@Test
	void depositWritesDebitOnExternalAndCreditOnTarget() {
		lockReturns(account(EXTERNAL_ID, AccountType.EXTERNAL, Currency.EUR, 0),
				account(TARGET_ID, AccountType.CUSTOMER, Currency.EUR, 0));

		service.deposit(ADMIN, "key", TARGET_ID, 2_500, Currency.EUR);

		verify(ledger).recordTransfer(900, EXTERNAL_ID, TARGET_ID, 2_500, Currency.EUR);
	}

	@Test
	void depositLocksTargetAndExternalTogether() {
		lockReturns(account(EXTERNAL_ID, AccountType.EXTERNAL, Currency.EUR, 0),
				account(TARGET_ID, AccountType.CUSTOMER, Currency.EUR, 0));

		service.deposit(ADMIN, "key", TARGET_ID, 10, Currency.EUR);

		verify(locking).lockInIdOrder(TARGET_ID, EXTERNAL_ID);
	}

	@Test
	void idempotencyIsScopedToTheCallerAndKey() {
		lockReturns(account(EXTERNAL_ID, AccountType.EXTERNAL, Currency.EUR, 0),
				account(TARGET_ID, AccountType.CUSTOMER, Currency.EUR, 0));

		service.deposit(ADMIN, "my-key", TARGET_ID, 10, Currency.EUR);

		verify(idempotency).execute(eq("admin"), eq("my-key"), anyString(), eq(TransferResponse.class), eq(201), any());
	}

	@Test
	void depositIntoAnExternalAccountIsRejected() {
		Account otherExternal = account(2, AccountType.EXTERNAL, Currency.EUR, 0);
		lockReturns(account(EXTERNAL_ID, AccountType.EXTERNAL, Currency.EUR, 0), otherExternal);

		assertThatThrownBy(() -> service.deposit(ADMIN, "key", 2, 10, Currency.EUR))
				.isInstanceOfSatisfying(LedgerException.class,
						ex -> assertThat(ex.code()).isEqualTo(ErrorCode.EXTERNAL_ACCOUNT_NOT_ALLOWED));
		assertNothingWritten();
	}

	@Test
	void depositIntoTheCurrencysOwnExternalAccountIsRejected() {
		Account external = account(EXTERNAL_ID, AccountType.EXTERNAL, Currency.EUR, 0);
		lockReturns(external);

		assertThatThrownBy(() -> service.deposit(ADMIN, "key", EXTERNAL_ID, 10, Currency.EUR))
				.isInstanceOfSatisfying(LedgerException.class,
						ex -> assertThat(ex.code()).isEqualTo(ErrorCode.EXTERNAL_ACCOUNT_NOT_ALLOWED));
		assertThat(external.getBalance()).isZero();
		assertNothingWritten();
	}

	@Test
	void depositInTheWrongCurrencyIsRejected() {
		Account external = account(EXTERNAL_ID, AccountType.EXTERNAL, Currency.EUR, 0);
		Account usdTarget = account(TARGET_ID, AccountType.CUSTOMER, Currency.USD, 0);
		lockReturns(external, usdTarget);

		assertThatThrownBy(() -> service.deposit(ADMIN, "key", TARGET_ID, 10, Currency.EUR))
				.isInstanceOfSatisfying(LedgerException.class,
						ex -> assertThat(ex.code()).isEqualTo(ErrorCode.CURRENCY_MISMATCH));
		assertThat(external.getBalance()).isZero();
		assertThat(usdTarget.getBalance()).isZero();
		assertNothingWritten();
	}

	@Test
	void depositToAMissingAccountIsNotFound() {
		when(locking.lockInIdOrder(anyLong(), anyLong())).thenThrow(new NotFoundException("Account not found"));

		assertThatThrownBy(() -> service.deposit(ADMIN, "key", 999, 10, Currency.EUR))
				.isInstanceOf(NotFoundException.class);
		assertNothingWritten();
	}

	@Test
	void sameRequestHashForSameRequestAndDifferentHashForDifferentAmount() {
		lockReturns(account(EXTERNAL_ID, AccountType.EXTERNAL, Currency.EUR, 0),
				account(TARGET_ID, AccountType.CUSTOMER, Currency.EUR, 0));
		ArgumentCaptor<String> hashes = ArgumentCaptor.forClass(String.class);

		service.deposit(ADMIN, "k", TARGET_ID, 10, Currency.EUR);
		service.deposit(ADMIN, "k", TARGET_ID, 10, Currency.EUR);
		service.deposit(ADMIN, "k", TARGET_ID, 11, Currency.EUR);

		verify(idempotency, org.mockito.Mockito.times(3)).execute(anyString(), anyString(), hashes.capture(), any(), anyInt(), any());
		assertThat(hashes.getAllValues().get(0)).isEqualTo(hashes.getAllValues().get(1));
		assertThat(hashes.getAllValues().get(0)).isNotEqualTo(hashes.getAllValues().get(2));
	}

	private void assertNothingWritten() {
		verify(transfers, never()).save(any());
		verify(ledger, never()).recordTransfer(anyLong(), anyLong(), anyLong(), anyLong(), any());
	}

}
