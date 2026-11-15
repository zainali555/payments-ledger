package com.zainali.ledger.transfer;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface TransferRepository extends JpaRepository<Transfer, Long> {

	/**
	 * The immutable facts of a transfer as plain values, so no {@link Transfer} entity enters the persistence context
	 * before the locks are taken. A later {@link #findByIdForUpdate} then reads the current status.
	 */
	@Query("select new com.zainali.ledger.transfer.TransferParties(t.type, t.sourceAccountId, t.targetAccountId) "
			+ "from Transfer t where t.id = :id")
	Optional<TransferParties> findPartiesById(@Param("id") long id);

	/** SELECT ... FOR UPDATE. Taken after the account locks (DECISIONS 8, global lock order). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from Transfer t where t.id = :id")
	Optional<Transfer> findByIdForUpdate(@Param("id") long id);

}
