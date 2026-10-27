package com.zainali.ledger.common;

public final class ApiHeaders {

	public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

	/** Set to {@code true} on a response replayed from a stored idempotency key. */
	public static final String IDEMPOTENCY_REPLAYED = "Idempotency-Replayed";

	private ApiHeaders() {
	}

}
