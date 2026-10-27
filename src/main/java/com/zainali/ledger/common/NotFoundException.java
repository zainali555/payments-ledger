package com.zainali.ledger.common;

/**
 * The resource does not exist or is not visible to the caller. The two cases must stay indistinguishable.
 */
public class NotFoundException extends RuntimeException {

	public NotFoundException(String message) {
		super(message);
	}

}
