package com.zainali.ledger.account;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface AccountRepository extends JpaRepository<Account, Long> {

	Optional<Account> findByIdAndOwnerId(Long id, String ownerId);

	List<Account> findByOwnerIdOrderByIdAsc(String ownerId);

	List<Account> findAllByOrderByIdAsc();

}
