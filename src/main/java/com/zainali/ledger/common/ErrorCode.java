package com.zainali.ledger.common;

import org.springframework.http.HttpStatus;

/**
 * Stable error codes for failures raised through {@link LedgerException}, each with its HTTP status.
 * {@code NOT_FOUND}, {@code FORBIDDEN} and {@code INTERNAL_ERROR} are produced by their own exception types.
 */
public enum ErrorCode {
	VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
	IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST),
	IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_CONTENT),
	INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_CONTENT),
	CURRENCY_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT),
	EXTERNAL_ACCOUNT_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_CONTENT);

	private final HttpStatus status;

	ErrorCode(HttpStatus status) {
		this.status = status;
	}

	public HttpStatus status() {
		return status;
	}

}
