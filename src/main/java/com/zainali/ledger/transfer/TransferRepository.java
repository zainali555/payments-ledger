package com.zainali.ledger.transfer;

import org.springframework.data.jpa.repository.JpaRepository;

interface TransferRepository extends JpaRepository<Transfer, Long> {
}
