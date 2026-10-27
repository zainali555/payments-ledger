package com.zainali.ledger.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import com.zainali.ledger.common.Caller;
import com.zainali.ledger.common.Currency;
import com.zainali.ledger.common.NotFoundException;

class AccountServiceTests {

	private final AccountRepository repository = mock(AccountRepository.class);
	private final AccountService service = new AccountService(repository);

	private static final Caller ALICE = new Caller("alice", false);
	private static final Caller ADMIN = new Caller("admin", true);

	private static Account customerAccount(long id, String owner) {
		Account account = Account.openCustomer(owner, Currency.EUR);
		org.springframework.test.util.ReflectionTestUtils.setField(account, "id", id);
		return account;
	}

	// --- create ---

	@Test
	void createdAccountIsOwnedByCallerWhenNoOwnerGiven() {
		when(repository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

		Account created = service.create(ALICE, Currency.USD, null);

		assertThat(created.getOwnerId()).isEqualTo("alice");
		assertThat(created.getType()).isEqualTo(AccountType.CUSTOMER);
		assertThat(created.getCurrency()).isEqualTo(Currency.USD);
		assertThat(created.getBalance()).isZero();
	}

	@Test
	void userCannotCreateAccountForAnotherOwner() {
		assertThatThrownBy(() -> service.create(ALICE, Currency.EUR, "bob"))
				.isInstanceOf(AccessDeniedException.class);
		verify(repository, never()).save(any());
	}

	@Test
	void userMayNameThemselvesAsOwner() {
		when(repository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

		assertThat(service.create(ALICE, Currency.EUR, "alice").getOwnerId()).isEqualTo("alice");
	}

	@Test
	void adminMaySetTheOwner() {
		when(repository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

		assertThat(service.create(ADMIN, Currency.EUR, "bob").getOwnerId()).isEqualTo("bob");
	}

	// --- get ---

	@Test
	void ownerGetsOwnAccount() {
		Account account = customerAccount(7, "alice");
		when(repository.findByIdAndOwnerId(7L, "alice")).thenReturn(Optional.of(account));

		assertThat(service.get(ALICE, 7L)).isSameAs(account);
	}

	@Test
	void userGetsNotFoundForAccountTheyDoNotOwn() {
		when(repository.findByIdAndOwnerId(7L, "alice")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.get(ALICE, 7L)).isInstanceOf(NotFoundException.class);
	}

	@Test
	void adminGetsAnyAccount() {
		Account account = customerAccount(7, "bob");
		when(repository.findById(7L)).thenReturn(Optional.of(account));

		assertThat(service.get(ADMIN, 7L)).isSameAs(account);
	}

	@Test
	void adminGetsNotFoundForMissingAccount() {
		when(repository.findById(7L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.get(ADMIN, 7L)).isInstanceOf(NotFoundException.class);
	}

	// --- list ---

	@Test
	void userListsOnlyOwnAccounts() {
		List<Account> own = List.of(customerAccount(1, "alice"));
		when(repository.findByOwnerIdOrderByIdAsc("alice")).thenReturn(own);

		assertThat(service.list(ALICE)).isEqualTo(own);
		verify(repository, never()).findAllByOrderByIdAsc();
	}

	@Test
	void adminListsAllAccounts() {
		List<Account> all = List.of(customerAccount(1, "alice"), customerAccount(2, "bob"));
		when(repository.findAllByOrderByIdAsc()).thenReturn(all);

		assertThat(service.list(ADMIN)).isEqualTo(all);
	}

}
