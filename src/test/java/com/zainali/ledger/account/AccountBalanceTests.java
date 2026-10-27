package com.zainali.ledger.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.zainali.ledger.common.Currency;
import com.zainali.ledger.common.ErrorCode;
import com.zainali.ledger.common.LedgerException;

class AccountBalanceTests {

	private static Account customer(long balance) {
		Account account = Account.openCustomer("alice", Currency.EUR);
		ReflectionTestUtils.setField(account, "balance", balance);
		return account;
	}

	private static Account external(long balance) {
		Account account = Account.openCustomer(null, Currency.EUR);
		ReflectionTestUtils.setField(account, "type", AccountType.EXTERNAL);
		ReflectionTestUtils.setField(account, "balance", balance);
		return account;
	}

	@Test
	void creditAddsToTheBalance() {
		Account account = customer(100);

		account.credit(250);

		assertThat(account.getBalance()).isEqualTo(350);
	}

	@Test
	void debitSubtractsFromTheBalance() {
		Account account = customer(100);

		account.debit(40);

		assertThat(account.getBalance()).isEqualTo(60);
	}

	@Test
	void customerCanDebitExactlyTheWholeBalance() {
		Account account = customer(100);

		account.debit(100);

		assertThat(account.getBalance()).isZero();
	}

	@Test
	void customerCannotDebitMoreThanTheBalance() {
		Account account = customer(100);

		assertThatThrownBy(() -> account.debit(101))
				.isInstanceOfSatisfying(LedgerException.class,
						ex -> assertThat(ex.code()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS));
		assertThat(account.getBalance()).isEqualTo(100);
	}

	@Test
	void externalAccountMayGoNegative() {
		Account account = external(0);

		account.debit(500);

		assertThat(account.getBalance()).isEqualTo(-500);
	}

	@Test
	void nonPositiveAmountsAreRejected() {
		Account account = customer(100);

		assertThatThrownBy(() -> account.credit(0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> account.credit(-1)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> account.debit(0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> account.debit(-1)).isInstanceOf(IllegalArgumentException.class);
		assertThat(account.getBalance()).isEqualTo(100);
	}

	@Test
	void creditThatWouldOverflowFailsInsteadOfWrapping() {
		Account account = customer(Long.MAX_VALUE);

		assertThatThrownBy(() -> account.credit(1)).isInstanceOf(ArithmeticException.class);
		assertThat(account.getBalance()).isEqualTo(Long.MAX_VALUE);
	}

	@Test
	void externalDebitThatWouldOverflowFailsInsteadOfWrapping() {
		Account account = external(Long.MIN_VALUE);

		assertThatThrownBy(() -> account.debit(1)).isInstanceOf(ArithmeticException.class);
	}

}
