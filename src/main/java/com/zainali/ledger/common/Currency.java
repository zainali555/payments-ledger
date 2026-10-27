package com.zainali.ledger.common;

/**
 * Supported currencies. Must match the check constraint on {@code accounts.currency}; adding one needs a new migration.
 */
public enum Currency {
	EUR,
	USD
}
