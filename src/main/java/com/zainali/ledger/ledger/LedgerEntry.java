package com.zainali.ledger.ledger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

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
 * Append-only: the database rejects updates and deletes, and this class has no setters. {@code amount} is positive and
 * in minor units; the direction says which way it moves.
 */
@Entity
@Table(name = "ledger_entries")
class LedgerEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "transfer_id")
	private long transferId;

	@Column(name = "account_id")
	private long accountId;

	@Enumerated(EnumType.STRING)
	private EntryDirection direction;

	private long amount;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.CHAR)
	private Currency currency;

	@Column(name = "created_at")
	private Instant createdAt;

	protected LedgerEntry() {
	}

	static LedgerEntry of(EntryDirection direction, long transferId, long accountId, long amount, Currency currency) {
		LedgerEntry entry = new LedgerEntry();
		entry.direction = direction;
		entry.transferId = transferId;
		entry.accountId = accountId;
		entry.amount = amount;
		entry.currency = currency;
		entry.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
		return entry;
	}

	Long getId() {
		return id;
	}

	long getTransferId() {
		return transferId;
	}

	long getAccountId() {
		return accountId;
	}

	EntryDirection getDirection() {
		return direction;
	}

	long getAmount() {
		return amount;
	}

	Currency getCurrency() {
		return currency;
	}

}
