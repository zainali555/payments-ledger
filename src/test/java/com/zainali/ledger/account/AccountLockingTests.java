package com.zainali.ledger.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.zainali.ledger.common.Currency;
import com.zainali.ledger.common.NotFoundException;

/** The lock order is what prevents deadlocks (DECISIONS 8), so it is pinned here independently of the database. */
class AccountLockingTests {

	private final AccountRepository repository = mock(AccountRepository.class);
	private final AccountLocking locking = new AccountLocking(repository);

	private static Account account(long id) {
		Account account = Account.openCustomer("owner-" + id, Currency.EUR);
		ReflectionTestUtils.setField(account, "id", id);
		return account;
	}

	private void exists(long... ids) {
		for (long id : ids) {
			when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(account(id)));
		}
	}

	@Test
	void locksInAscendingIdOrderWhateverTheArgumentOrder() {
		exists(3, 7);

		Map<Long, Account> locked = locking.lockInIdOrder(7, 3);

		InOrder order = inOrder(repository);
		order.verify(repository).findByIdForUpdate(3);
		order.verify(repository).findByIdForUpdate(7);
		assertThat(locked.keySet()).containsExactly(3L, 7L);
	}

	@Test
	void bothDirectionsBetweenTheSameAccountsTakeTheLocksInTheSameOrder() {
		exists(3, 7);

		locking.lockInIdOrder(3, 7);
		locking.lockInIdOrder(7, 3);

		InOrder order = inOrder(repository);
		order.verify(repository).findByIdForUpdate(3);
		order.verify(repository).findByIdForUpdate(7);
		order.verify(repository).findByIdForUpdate(3);
		order.verify(repository).findByIdForUpdate(7);
	}

	@Test
	void sortsNumericallyNotAsText() {
		exists(9, 10, 100);

		locking.lockInIdOrder(100, 9, 10);

		InOrder order = inOrder(repository);
		order.verify(repository).findByIdForUpdate(9);
		order.verify(repository).findByIdForUpdate(10);
		order.verify(repository).findByIdForUpdate(100);
	}

	@Test
	void duplicateIdsAreLockedOnce() {
		exists(5);

		Map<Long, Account> locked = locking.lockInIdOrder(5, 5);

		verify(repository, times(1)).findByIdForUpdate(5);
		verifyNoMoreInteractions(repository);
		assertThat(locked).containsOnlyKeys(5L);
	}

	@Test
	void aMissingAccountIsNotFoundAndStopsBeforeLockingHigherIds() {
		exists(8);
		when(repository.findByIdForUpdate(4)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> locking.lockInIdOrder(8, 4)).isInstanceOf(NotFoundException.class);

		verify(repository).findByIdForUpdate(4);
		verify(repository, times(0)).findByIdForUpdate(8);
		verify(repository, times(1)).findByIdForUpdate(anyLong());
	}

}
