package com.zainali.ledger.account;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.zainali.ledger.common.Currency;

import jakarta.persistence.LockModeType;

interface AccountRepository extends JpaRepository<Account, Long> {

	Optional<Account> findByIdAndOwnerId(Long id, String ownerId);

	List<Account> findByOwnerIdOrderByIdAsc(String ownerId);

	List<Account> findAllByOrderByIdAsc();

	/** SELECT ... FOR UPDATE. Load an account this way before any plain read, or Hibernate returns the stale instance. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select a from Account a where a.id = :id")
	Optional<Account> findByIdForUpdate(@Param("id") long id);

	/** Only the id, so no entity is cached before the lock is taken. */
	@Query("select a.id from Account a where a.type = :type and a.currency = :currency")
	Optional<Long> findIdByTypeAndCurrency(@Param("type") AccountType type, @Param("currency") Currency currency);

}
