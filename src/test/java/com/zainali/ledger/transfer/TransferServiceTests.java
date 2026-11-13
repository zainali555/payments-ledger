package com.zainali.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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

class TransferServiceTests {

	private static final Caller ALICE = new Caller("alice", false);
	private static final Caller ADMIN = new Caller("admin", true);
	private static final long SOURCE_ID = 10;
	private static final long TARGET_ID = 20;
	private static final long EXTERNAL_ID = 1;

	private final IdempotencyService idempotency = mock(IdempotencyService.class);
	private final AccountLocking locking = mock(AccountLocking.class);
	private final TransferRepository transfers = mock(TransferRepository.class);
	private final LedgerService ledger = mock(LedgerService.class);
	private final TransferService service = new TransferService(idempotency, locking, transfers, ledger);

	@BeforeEach
	@SuppressWarnings("unchecked")
	void runIdempotentWorkInline() {
		when(idempotency.execute(anyString(), anyString(), anyString(), any(), anyInt(), any()))
				.thenAnswer(inv -> new IdempotentResult<>(inv.getArgument(4), ((Supplier<Object>) inv.getArgument(5)).get(), false));
		when(transfers.save(any(Transfer.class))).thenAnswer(inv -> {
			Transfer transfer = inv.getArgument(0);
			ReflectionTestUtils.setField(transfer, "id", 900L);
			return transfer;
		});
		when(locking.ownerIdOf(SOURCE_ID)).thenReturn(Optional.of("alice"));
		when(locking.ownerIdOf(TARGET_ID)).thenReturn(Optional.of("bob"));
		when(locking.ownerIdOf(EXTERNAL_ID)).thenReturn(Optional.empty());
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

	private static Account customer(long id, Currency currency, long balance) {
		return account(id, AccountType.CUSTOMER, currency, balance);
	}

	private void lockReturns(Account... accounts) {
		Map<Long, Account> locked = new LinkedHashMap<>();
		for (Account account : accounts) {
			locked.put(account.getId(), account);
		}
		when(locking.lockInIdOrder(anyLong(), anyLong())).thenReturn(locked);
	}

	private IdempotentResult<TransferResponse> transfer(Caller caller, long source, long target, long amount, Currency currency) {
		return service.transfer(caller, "key", source, target, amount, currency);
	}

	private void assertRejectedWith(ErrorCode code, Runnable call) {
		assertThatThrownBy(call::run).isInstanceOfSatisfying(LedgerException.class, ex -> assertThat(ex.code()).isEqualTo(code));
		assertNothingWritten();
	}

	private void assertNothingWritten() {
		verify(transfers, never()).save(any());
		verify(ledger, never()).recordTransfer(anyLong(), anyLong(), anyLong(), anyLong(), any());
	}

	// --- success ---

	@Test
	void ownerTransferMovesMoneyFromSourceToTarget() {
		Account source = customer(SOURCE_ID, Currency.EUR, 1_000);
		Account target = customer(TARGET_ID, Currency.EUR, 50);
		lockReturns(source, target);

		IdempotentResult<TransferResponse> result = transfer(ALICE, SOURCE_ID, TARGET_ID, 400, Currency.EUR);

		assertThat(source.getBalance()).isEqualTo(600);
		assertThat(target.getBalance()).isEqualTo(450);
		assertThat(result.status()).isEqualTo(201);
		assertThat(result.replayed()).isFalse();
		assertThat(result.body()).satisfies(body -> {
			assertThat(body.id()).isEqualTo(900);
			assertThat(body.type()).isEqualTo(TransferType.TRANSFER);
			assertThat(body.status()).isEqualTo(TransferStatus.COMPLETED);
			assertThat(body.sourceAccountId()).isEqualTo(SOURCE_ID);
			assertThat(body.targetAccountId()).isEqualTo(TARGET_ID);
			assertThat(body.amount()).isEqualTo(400);
			assertThat(body.currency()).isEqualTo(Currency.EUR);
		});
	}

	@Test
	void transferRecordsDebitOnSourceAndCreditOnTargetAndTheCallerAsCreator() {
		lockReturns(customer(SOURCE_ID, Currency.EUR, 1_000), customer(TARGET_ID, Currency.EUR, 0));

		transfer(ALICE, SOURCE_ID, TARGET_ID, 400, Currency.EUR);

		ArgumentCaptor<Transfer> saved = ArgumentCaptor.forClass(Transfer.class);
		verify(transfers).save(saved.capture());
		assertThat(saved.getValue().getCreatedBy()).isEqualTo("alice");
		assertThat(saved.getValue().getType()).isEqualTo(TransferType.TRANSFER);
		verify(ledger).recordTransfer(900, SOURCE_ID, TARGET_ID, 400, Currency.EUR);
	}

	@Test
	void transferOfTheWholeBalanceIsAllowed() {
		Account source = customer(SOURCE_ID, Currency.EUR, 400);
		lockReturns(source, customer(TARGET_ID, Currency.EUR, 0));

		transfer(ALICE, SOURCE_ID, TARGET_ID, 400, Currency.EUR);

		assertThat(source.getBalance()).isZero();
	}

	@Test
	void adminCanTransferBetweenAnyTwoAccountsWithoutAnOwnershipLookup() {
		Account source = customer(SOURCE_ID, Currency.EUR, 1_000);
		lockReturns(source, customer(TARGET_ID, Currency.EUR, 0));

		transfer(ADMIN, SOURCE_ID, TARGET_ID, 100, Currency.EUR);

		assertThat(source.getBalance()).isEqualTo(900);
		verify(locking, never()).ownerIdOf(anyLong());
	}

	@Test
	void ownershipIsCheckedWithoutLoadingTheAccountAndBeforeTheLockIsTaken() {
		lockReturns(customer(SOURCE_ID, Currency.EUR, 1_000), customer(TARGET_ID, Currency.EUR, 0));

		transfer(ALICE, SOURCE_ID, TARGET_ID, 100, Currency.EUR);

		InOrder order = inOrder(locking);
		order.verify(locking).ownerIdOf(SOURCE_ID);
		order.verify(locking).lockInIdOrder(SOURCE_ID, TARGET_ID);
	}

	@Test
	void idempotencyIsScopedToTheCallerAndKey() {
		lockReturns(customer(SOURCE_ID, Currency.EUR, 1_000), customer(TARGET_ID, Currency.EUR, 0));

		service.transfer(ALICE, "my-key", SOURCE_ID, TARGET_ID, 100, Currency.EUR);

		verify(idempotency).execute(eq("alice"), eq("my-key"), anyString(), eq(TransferResponse.class), eq(201), any());
	}

	@Test
	void requestHashDependsOnEveryField() {
		// Only the hash is under test here, so the work itself is not run.
		doReturn(new IdempotentResult<>(201, null, false)).when(idempotency).execute(anyString(), anyString(), anyString(),
				any(), anyInt(), any());
		ArgumentCaptor<String> hashes = ArgumentCaptor.forClass(String.class);

		transfer(ADMIN, SOURCE_ID, TARGET_ID, 10, Currency.EUR);
		transfer(ADMIN, SOURCE_ID, TARGET_ID, 10, Currency.EUR);
		transfer(ADMIN, SOURCE_ID, TARGET_ID, 11, Currency.EUR);
		transfer(ADMIN, TARGET_ID, SOURCE_ID, 10, Currency.EUR);
		transfer(ADMIN, SOURCE_ID, TARGET_ID, 10, Currency.USD);

		verify(idempotency, times(5)).execute(anyString(), anyString(), hashes.capture(), any(), anyInt(), any());
		assertThat(hashes.getAllValues().get(0)).isEqualTo(hashes.getAllValues().get(1));
		assertThat(hashes.getAllValues().subList(2, 5)).doesNotContain(hashes.getAllValues().get(0)).doesNotHaveDuplicates();
	}

	// --- rules ---

	@Test
	void sameAccountIsRejected() {
		assertRejectedWith(ErrorCode.SAME_ACCOUNT, () -> transfer(ALICE, SOURCE_ID, SOURCE_ID, 10, Currency.EUR));
		verify(locking, never()).lockInIdOrder(anyLong(), anyLong());
	}

	@Test
	void sameAccountIsRejectedForAnAdminToo() {
		assertRejectedWith(ErrorCode.SAME_ACCOUNT, () -> transfer(ADMIN, SOURCE_ID, SOURCE_ID, 10, Currency.EUR));
	}

	@Test
	void sameAccountOnSomeoneElsesAccountIsNotFoundSoNothingLeaks() {
		assertThatThrownBy(() -> transfer(ALICE, TARGET_ID, TARGET_ID, 10, Currency.EUR))
				.isInstanceOf(NotFoundException.class);
		assertNothingWritten();
	}

	@Test
	void sendingFromSomeoneElsesAccountIsNotFound() {
		assertThatThrownBy(() -> transfer(ALICE, TARGET_ID, SOURCE_ID, 10, Currency.EUR))
				.isInstanceOf(NotFoundException.class);
		verify(locking, never()).lockInIdOrder(anyLong(), anyLong());
		assertNothingWritten();
	}

	@Test
	void sendingFromAMissingAccountIsNotFoundForAUser() {
		when(locking.ownerIdOf(999)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> transfer(ALICE, 999, TARGET_ID, 10, Currency.EUR)).isInstanceOf(NotFoundException.class);
		assertNothingWritten();
	}

	@Test
	void userCannotSendFromAnExternalAccountAndLearnsNothingAboutIt() {
		assertThatThrownBy(() -> transfer(ALICE, EXTERNAL_ID, SOURCE_ID, 10, Currency.EUR))
				.isInstanceOf(NotFoundException.class);
		assertNothingWritten();
	}

	@Test
	void sendingToAMissingAccountIsNotFound() {
		when(locking.lockInIdOrder(anyLong(), anyLong())).thenThrow(new NotFoundException("Account not found"));

		assertThatThrownBy(() -> transfer(ALICE, SOURCE_ID, 999, 10, Currency.EUR)).isInstanceOf(NotFoundException.class);
		assertNothingWritten();
	}

	@Test
	void sendingToAnExternalAccountIsRejected() {
		Account source = customer(SOURCE_ID, Currency.EUR, 1_000);
		Account external = account(EXTERNAL_ID, AccountType.EXTERNAL, Currency.EUR, 0);
		lockReturns(external, source);

		assertRejectedWith(ErrorCode.EXTERNAL_ACCOUNT_NOT_ALLOWED,
				() -> transfer(ALICE, SOURCE_ID, EXTERNAL_ID, 10, Currency.EUR));
		assertThat(source.getBalance()).isEqualTo(1_000);
		assertThat(external.getBalance()).isZero();
	}

	@Test
	void adminCannotSendFromAnExternalAccount() {
		Account external = account(EXTERNAL_ID, AccountType.EXTERNAL, Currency.EUR, 0);
		lockReturns(external, customer(TARGET_ID, Currency.EUR, 0));

		assertRejectedWith(ErrorCode.EXTERNAL_ACCOUNT_NOT_ALLOWED,
				() -> transfer(ADMIN, EXTERNAL_ID, TARGET_ID, 10, Currency.EUR));
		assertThat(external.getBalance()).isZero();
	}

	@Test
	void currencyMismatchBetweenAccountsIsRejected() {
		Account source = customer(SOURCE_ID, Currency.EUR, 1_000);
		Account target = customer(TARGET_ID, Currency.USD, 0);
		lockReturns(source, target);

		assertRejectedWith(ErrorCode.CURRENCY_MISMATCH, () -> transfer(ALICE, SOURCE_ID, TARGET_ID, 10, Currency.EUR));
		assertThat(source.getBalance()).isEqualTo(1_000);
		assertThat(target.getBalance()).isZero();
	}

	@Test
	void currencyMismatchMessageDoesNotNameTheCurrencies() {
		lockReturns(customer(SOURCE_ID, Currency.EUR, 1_000), customer(TARGET_ID, Currency.USD, 0));

		assertThatThrownBy(() -> transfer(ALICE, SOURCE_ID, TARGET_ID, 10, Currency.EUR))
				.isInstanceOf(LedgerException.class).message().doesNotContain("EUR").doesNotContain("USD");
	}

	@Test
	void requestCurrencyThatDiffersFromBothAccountsIsRejected() {
		lockReturns(customer(SOURCE_ID, Currency.EUR, 1_000), customer(TARGET_ID, Currency.EUR, 0));

		assertRejectedWith(ErrorCode.CURRENCY_MISMATCH, () -> transfer(ALICE, SOURCE_ID, TARGET_ID, 10, Currency.USD));
	}

	@Test
	void insufficientFundsIsRejectedAndNothingChanges() {
		Account source = customer(SOURCE_ID, Currency.EUR, 399);
		Account target = customer(TARGET_ID, Currency.EUR, 0);
		lockReturns(source, target);

		assertRejectedWith(ErrorCode.INSUFFICIENT_FUNDS, () -> transfer(ALICE, SOURCE_ID, TARGET_ID, 400, Currency.EUR));
		assertThat(source.getBalance()).isEqualTo(399);
		assertThat(target.getBalance()).isZero();
	}

	// --- reads ---

	private static Transfer storedTransfer(long source, long target) {
		Transfer transfer = Transfer.completed(TransferType.TRANSFER, source, target, 100, Currency.EUR, "alice");
		ReflectionTestUtils.setField(transfer, "id", 77L);
		ReflectionTestUtils.setField(transfer, "createdAt", Instant.parse("2026-01-01T00:00:00Z"));
		return transfer;
	}

	@Test
	void ownerOfTheSourceCanReadTheTransfer() {
		when(transfers.findById(77L)).thenReturn(Optional.of(storedTransfer(SOURCE_ID, TARGET_ID)));

		assertThat(service.get(ALICE, 77).id()).isEqualTo(77);
	}

	@Test
	void ownerOfTheTargetCanReadTheTransfer() {
		when(transfers.findById(77L)).thenReturn(Optional.of(storedTransfer(SOURCE_ID, TARGET_ID)));

		assertThat(service.get(new Caller("bob", false), 77).id()).isEqualTo(77);
	}

	@Test
	void adminCanReadAnyTransfer() {
		when(transfers.findById(77L)).thenReturn(Optional.of(storedTransfer(SOURCE_ID, TARGET_ID)));

		assertThat(service.get(ADMIN, 77).id()).isEqualTo(77);
	}

	@Test
	void aStrangerCannotReadTheTransferAndSeesNotFound() {
		when(transfers.findById(77L)).thenReturn(Optional.of(storedTransfer(SOURCE_ID, TARGET_ID)));

		assertThatThrownBy(() -> service.get(new Caller("mallory", false), 77)).isInstanceOf(NotFoundException.class);
	}

	@Test
	void aMissingTransferIsNotFound() {
		when(transfers.findById(78L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.get(ALICE, 78)).isInstanceOf(NotFoundException.class);
	}

	@Test
	void theReceiverOfADepositCanReadIt() {
		Transfer deposit = Transfer.completed(TransferType.DEPOSIT, EXTERNAL_ID, SOURCE_ID, 100, Currency.EUR, "admin");
		ReflectionTestUtils.setField(deposit, "id", 78L);
		when(transfers.findById(78L)).thenReturn(Optional.of(deposit));

		assertThat(service.get(ALICE, 78).type()).isEqualTo(TransferType.DEPOSIT);
	}

}
