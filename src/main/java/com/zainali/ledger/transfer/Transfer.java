package com.zainali.ledger.transfer;

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
 * Persistence model only; the API uses {@link TransferResponse}. {@code amount} is positive and in minor units.
 */
@Entity
@Table(name = "transfers")
class Transfer {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	private TransferType type;

	@Enumerated(EnumType.STRING)
	private TransferStatus status;

	@Column(name = "source_account_id")
	private long sourceAccountId;

	@Column(name = "target_account_id")
	private long targetAccountId;

	private long amount;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.CHAR)
	private Currency currency;

	@Column(name = "reverses_transfer_id")
	private Long reversesTransferId;

	@Column(name = "created_by")
	private String createdBy;

	@Column(name = "created_at")
	private Instant createdAt;

	protected Transfer() {
	}

	static Transfer completed(TransferType type, long sourceAccountId, long targetAccountId, long amount,
			Currency currency, String createdBy) {
		Transfer transfer = new Transfer();
		transfer.type = type;
		transfer.status = TransferStatus.COMPLETED;
		transfer.sourceAccountId = sourceAccountId;
		transfer.targetAccountId = targetAccountId;
		transfer.amount = amount;
		transfer.currency = currency;
		transfer.createdBy = createdBy;
		// Postgres stores microseconds; truncating keeps the response equal to what is read back later.
		transfer.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
		return transfer;
	}

	Long getId() {
		return id;
	}

	TransferType getType() {
		return type;
	}

	TransferStatus getStatus() {
		return status;
	}

	long getSourceAccountId() {
		return sourceAccountId;
	}

	long getTargetAccountId() {
		return targetAccountId;
	}

	long getAmount() {
		return amount;
	}

	Currency getCurrency() {
		return currency;
	}

	Long getReversesTransferId() {
		return reversesTransferId;
	}

	String getCreatedBy() {
		return createdBy;
	}

	Instant getCreatedAt() {
		return createdAt;
	}

}
