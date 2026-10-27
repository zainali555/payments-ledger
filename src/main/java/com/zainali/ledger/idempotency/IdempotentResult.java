package com.zainali.ledger.idempotency;

/** The outcome of an idempotent operation: the HTTP status and body to return, and whether they were replayed. */
public record IdempotentResult<T>(int status, T body, boolean replayed) {
}
