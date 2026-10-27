package com.zainali.ledger.common;

/**
 * Limits for amounts in minor units. The cap keeps balance arithmetic far from {@code long} overflow.
 */
public final class Money {

	/** Largest amount of a single deposit or transfer: 10^12 minor units. */
	public static final long MAX_AMOUNT = 1_000_000_000_000L;

	private Money() {
	}

}
