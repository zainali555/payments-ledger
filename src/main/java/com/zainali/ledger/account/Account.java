package com.zainali.ledger.account;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.zainali.ledger.common.Currency;
import com.zainali.ledger.common.ErrorCode;
import com.zainali.ledger.common.LedgerException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Persistence model only; the API uses {@link AccountResponse}. {@code balance} is in minor units.
 */
@Entity
@Table(name = "accounts")
public class Account {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "owner_id")
	private String ownerId;

	@Enumerated(EnumType.STRING)
	private AccountType type;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.CHAR)
	private Currency currency;

	private long balance;

	@Column(name = "created_at")
	private Instant createdAt;

	protected Account() {
	}

	/** Only CUSTOMER accounts can be opened through the API; EXTERNAL accounts are seeded by migration. */
	static Account openCustomer(String ownerId, Currency currency) {
		Account account = new Account();
		account.ownerId = ownerId;
		account.type = AccountType.CUSTOMER;
		account.currency = currency;
		account.balance = 0;
		account.createdAt = Instant.now();
		return account;
	}

	/** Adds to the cached balance. The caller must hold the row lock. */
	public void credit(long amount) {
		requirePositive(amount);
		balance = Math.addExact(balance, amount);
	}

	/** Subtracts from the cached balance. Only an EXTERNAL account may go negative. The caller must hold the row lock. */
	public void debit(long amount) {
		requirePositive(amount);
		if (type != AccountType.EXTERNAL && balance < amount) {
			throw new LedgerException(ErrorCode.INSUFFICIENT_FUNDS, "Insufficient funds");
		}
		balance = Math.subtractExact(balance, amount);
	}

	private static void requirePositive(long amount) {
		if (amount <= 0) {
			throw new IllegalArgumentException("Amount must be positive");
		}
	}

	public Long getId() {
		return id;
	}

	public String getOwnerId() {
		return ownerId;
	}

	public AccountType getType() {
		return type;
	}

	public Currency getCurrency() {
		return currency;
	}

	public long getBalance() {
		return balance;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
