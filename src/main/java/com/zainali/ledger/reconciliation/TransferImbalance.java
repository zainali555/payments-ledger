package com.zainali.ledger.reconciliation;

/** A transfer whose signed entries (credits positive, debits negative) do not sum to zero. */
public record TransferImbalance(long transferId, long signedSum) {
}
