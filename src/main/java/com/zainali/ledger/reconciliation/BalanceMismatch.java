package com.zainali.ledger.reconciliation;

/** An account whose cached {@code accounts.balance} differs from the signed sum of its ledger entries. */
public record BalanceMismatch(long accountId, long cachedBalance, long entriesSum) {
}
