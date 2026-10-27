package com.zainali.ledger.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {
}
