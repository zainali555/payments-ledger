package com.zainali.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DataIntegrityViolationException;
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
import com.zainali.ledger.idempotency.RequestHash;
import com.zainali.ledger.ledger.LedgerService;

class ReversalServiceTests {

	private static final Caller ADMIN = new Caller("admin", true);
	private static final long ORIGINAL_ID = 500;
	private static final long REVERSAL_ID = 900;
	// The original moved money from SOURCE (higher id) to TARGET (lower id), so lock order differs from argument order.
	private static final long SOURCE_ID = 20;
	private static final long TARGET_ID = 10;

	private final IdempotencyService idempotency = mock(IdempotencyService.class);
	private final AccountLocking locking = mock(AccountLocking.class);
	private final TransferRepository transfers = mock(TransferRepository.class);
	private final LedgerService ledger = mock(LedgerService.class);
	private final ReversalService service = new ReversalService(idempotency, locking, transfers, ledger);

	private Account source;
	private Account target;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		when(idempotency.execute(anyString(), anyString(), anyString(), any(), anyInt(), any()))
				.thenAnswer(inv -> new IdempotentResult<>(inv.getArgument(4), ((Supplier<Object>) inv.getArgument(5)).get(), false));
		when(transfers.save(any(Transfer.class))).thenAnswer(inv -> {
			Transfer transfer = inv.getArgument(0);
			ReflectionTestUtils.setField(transfer, "id", REVERSAL_ID);
			return transfer;
		});
		source = customer(SOURCE_ID, 100);
		target = customer(TARGET_ID, 1_000);
		Map<Long, Account> locked = new LinkedHashMap<>();
		locked.put(TARGET_ID, target);
		locked.put(SOURCE_ID, source);
		when(locking.lockInIdOrder(anyLong(), anyLong())).thenReturn(locked);
	}

	private static Account customer(long id, long balance) {
		Account account = BeanUtils.instantiateClass(Account.class);
		ReflectionTestUtils.setField(account, "id", id);
		ReflectionTestUtils.setField(account, "currency", Currency.EUR);
		ReflectionTestUtils.setField(account, "type", AccountType.CUSTOMER);
		ReflectionTestUtils.setField(account, "balance", balance);
		return account;
	}

	private Transfer original(TransferType type, long amount) {
		Transfer original = Transfer.completed(type, SOURCE_ID, TARGET_ID, amount, Currency.EUR, "alice");
		ReflectionTestUtils.setField(original, "id", ORIGINAL_ID);
		when(transfers.findPartiesById(ORIGINAL_ID)).thenReturn(Optional.of(new TransferParties(type, SOURCE_ID, TARGET_ID)));
		when(transfers.findByIdForUpdate(ORIGINAL_ID)).thenReturn(Optional.of(original));
		return original;
	}

	private IdempotentResult<TransferResponse> reverse() {
		return service.reverse(ADMIN, "key", ORIGINAL_ID);
	}

	private void assertRejectedWith(ErrorCode code) {
		assertThatThrownBy(this::reverse).isInstanceOfSatisfying(LedgerException.class,
				ex -> assertThat(ex.code()).isEqualTo(code));
	}

	private void assertNothingWritten() {
		verify(transfers, never()).save(any());
		verify(ledger, never()).recordTransfer(anyLong(), anyLong(), anyLong(), anyLong(), any());
	}

	// --- success ---

	@Test
	void reversalMovesTheMoneyBackAndMarksTheOriginalReversed() {
		Transfer original = original(TransferType.TRANSFER, 300);

		IdempotentResult<TransferResponse> result = reverse();

		assertThat(target.getBalance()).isEqualTo(700);
		assertThat(source.getBalance()).isEqualTo(400);
		assertThat(original.getStatus()).isEqualTo(TransferStatus.REVERSED);
		assertThat(result.status()).isEqualTo(201);
		assertThat(result.body()).satisfies(body -> {
			assertThat(body.id()).isEqualTo(REVERSAL_ID);
			assertThat(body.type()).isEqualTo(TransferType.REVERSAL);
			assertThat(body.status()).isEqualTo(TransferStatus.COMPLETED);
			assertThat(body.sourceAccountId()).isEqualTo(TARGET_ID);
			assertThat(body.targetAccountId()).isEqualTo(SOURCE_ID);
			assertThat(body.amount()).isEqualTo(300);
			assertThat(body.currency()).isEqualTo(Currency.EUR);
			assertThat(body.reversesTransferId()).isEqualTo(ORIGINAL_ID);
		});
	}

	@Test
	void reversalSavesANewTransferAndTwoSwappedEntries() {
		original(TransferType.TRANSFER, 300);

		reverse();

		ArgumentCaptor<Transfer> saved = ArgumentCaptor.forClass(Transfer.class);
		verify(transfers).save(saved.capture());
		assertThat(saved.getValue().getType()).isEqualTo(TransferType.REVERSAL);
		assertThat(saved.getValue().getReversesTransferId()).isEqualTo(ORIGINAL_ID);
		assertThat(saved.getValue().getCreatedBy()).isEqualTo("admin");
		// Debit the original recipient, credit the original sender.
		verify(ledger).recordTransfer(REVERSAL_ID, TARGET_ID, SOURCE_ID, 300, Currency.EUR);
	}

	@Test
	void requestHashCoversTheOperationAndTheTransferId() {
		original(TransferType.TRANSFER, 300);

		reverse();

		verify(idempotency).execute(eq("admin"), eq("key"), eq(RequestHash.of("REVERSE", ORIGINAL_ID)),
				eq(TransferResponse.class), eq(201), any());
	}

	@Test
	void accountsAreLockedBeforeTheTransferRowIsLoaded() {
		original(TransferType.TRANSFER, 300);

		reverse();

		InOrder order = inOrder(transfers, locking);
		order.verify(transfers).findPartiesById(ORIGINAL_ID);
		order.verify(locking).lockInIdOrder(SOURCE_ID, TARGET_ID);
		order.verify(transfers).findByIdForUpdate(ORIGINAL_ID);
		order.verify(transfers).save(any());
		verify(transfers, never()).findById(anyLong());
	}

	// --- rejections ---

	@Test
	void missingTransferIsNotFoundAndLocksNothing() {
		when(transfers.findPartiesById(ORIGINAL_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(this::reverse).isInstanceOf(NotFoundException.class);
		verifyNoInteractions(locking);
		assertNothingWritten();
	}

	@Test
	void depositIsNotReversibleAndLocksNothing() {
		original(TransferType.DEPOSIT, 300);

		assertRejectedWith(ErrorCode.NOT_REVERSIBLE);
		verifyNoInteractions(locking);
		assertNothingWritten();
	}

	@Test
	void reversalIsNotReversible() {
		original(TransferType.REVERSAL, 300);

		assertRejectedWith(ErrorCode.NOT_REVERSIBLE);
		verifyNoInteractions(locking);
		assertNothingWritten();
	}

	@Test
	void alreadyReversedTransferIsRejectedAndBalancesAreUntouched() {
		Transfer original = original(TransferType.TRANSFER, 300);
		original.markReversed();

		assertRejectedWith(ErrorCode.ALREADY_REVERSED);
		assertThat(target.getBalance()).isEqualTo(1_000);
		assertThat(source.getBalance()).isEqualTo(100);
		assertNothingWritten();
	}

	@Test
	void recipientWithoutTheFundsIsInsufficientFundsAndTheOriginalStaysCompleted() {
		Transfer original = original(TransferType.TRANSFER, 1_001);

		assertRejectedWith(ErrorCode.INSUFFICIENT_FUNDS);
		assertThat(original.getStatus()).isEqualTo(TransferStatus.COMPLETED);
		assertThat(target.getBalance()).isEqualTo(1_000);
		assertThat(source.getBalance()).isEqualTo(100);
		assertNothingWritten();
	}

	@Test
	void uniqueReversalViolationIsReportedAsAlreadyReversed() {
		original(TransferType.TRANSFER, 300);
		when(transfers.save(any(Transfer.class))).thenThrow(new DataIntegrityViolationException("duplicate",
				new ConstraintViolationException("duplicate", new SQLException(), ReversalService.ONE_REVERSAL_PER_TRANSFER)));

		assertRejectedWith(ErrorCode.ALREADY_REVERSED);
	}

	@Test
	void otherIntegrityViolationsAreNotMasked() {
		original(TransferType.TRANSFER, 300);
		DataIntegrityViolationException other = new DataIntegrityViolationException("other",
				new ConstraintViolationException("other", new SQLException(), "transfers_amount_chk"));
		when(transfers.save(any(Transfer.class))).thenThrow(other);

		assertThatThrownBy(this::reverse).isSameAs(other);
	}

	@Test
	void markReversedRefusesAnythingButACompletedTransfer() {
		Transfer deposit = Transfer.completed(TransferType.DEPOSIT, 1, 2, 10, Currency.EUR, "admin");
		assertThatThrownBy(deposit::markReversed).isInstanceOf(IllegalStateException.class);

		Transfer transfer = Transfer.completed(TransferType.TRANSFER, 1, 2, 10, Currency.EUR, "alice");
		transfer.markReversed();
		assertThatThrownBy(transfer::markReversed).isInstanceOf(IllegalStateException.class);
	}

}
