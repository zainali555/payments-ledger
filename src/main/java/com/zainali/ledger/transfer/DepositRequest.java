package com.zainali.ledger.transfer;

import com.zainali.ledger.common.Currency;
import com.zainali.ledger.common.Money;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** {@code amount} is in minor units (cents). */
record DepositRequest(
		@NotNull @Positive @Max(Money.MAX_AMOUNT) Long amount,
		@NotNull Currency currency) {
}
