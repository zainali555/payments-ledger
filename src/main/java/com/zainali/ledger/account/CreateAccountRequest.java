package com.zainali.ledger.account;

import com.zainali.ledger.common.Currency;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code ownerId} is optional and defaults to the caller; only an ADMIN may set it to someone else.
 * There is no account type: the API only creates CUSTOMER accounts.
 */
record CreateAccountRequest(
		@NotNull Currency currency,
		@Size(min = 1, max = 255) String ownerId) {
}
