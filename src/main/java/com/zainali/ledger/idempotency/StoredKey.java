package com.zainali.ledger.idempotency;

/** An idempotency key row. The response is {@code null} only while the owning request is still in flight. */
record StoredKey(String requestHash, Integer responseStatus, String responseBody) {
}
