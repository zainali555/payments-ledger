package com.zainali.ledger.account;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.zainali.ledger.common.Currency;

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
