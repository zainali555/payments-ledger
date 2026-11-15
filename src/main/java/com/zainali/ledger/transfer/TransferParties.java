package com.zainali.ledger.transfer;

/** The fields of a transfer that never change after it is written: its type and its two accounts. */
record TransferParties(TransferType type, long sourceAccountId, long targetAccountId) {
}
