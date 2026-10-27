package com.zainali.ledger.common;

/**
 * A request that is well-formed but breaks a ledger rule. The {@link ErrorCode} decides the status and the {@code code}
 * field of the problem response.
 */
public class LedgerException extends RuntimeException {

	private final ErrorCode code;

	public LedgerException(ErrorCode code, String message) {
		super(message);
		this.code = code;
	}

	public ErrorCode code() {
		return code;
	}

}
