package com.zainali.ledger.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RequestHashTests {

	@Test
	void isSha256AsSixtyFourHexCharacters() {
		assertThat(RequestHash.of("DEPOSIT", 1L, 500L, "EUR")).matches("[0-9a-f]{64}");
	}

	@Test
	void sameInputsGiveSameHash() {
		assertThat(RequestHash.of("DEPOSIT", 1L, 500L, "EUR")).isEqualTo(RequestHash.of("DEPOSIT", 1L, 500L, "EUR"));
	}

	@Test
	void differentOperationGivesDifferentHash() {
		assertThat(RequestHash.of("DEPOSIT", 1L, 500L)).isNotEqualTo(RequestHash.of("TRANSFER", 1L, 500L));
	}

	@Test
	void differentPartGivesDifferentHash() {
		assertThat(RequestHash.of("DEPOSIT", 1L, 500L, "EUR")).isNotEqualTo(RequestHash.of("DEPOSIT", 2L, 500L, "EUR"));
		assertThat(RequestHash.of("DEPOSIT", 1L, 500L, "EUR")).isNotEqualTo(RequestHash.of("DEPOSIT", 1L, 501L, "EUR"));
		assertThat(RequestHash.of("DEPOSIT", 1L, 500L, "EUR")).isNotEqualTo(RequestHash.of("DEPOSIT", 1L, 500L, "USD"));
	}

	@Test
	void partBoundariesAreNotAmbiguous() {
		assertThat(RequestHash.of("OP", "1", "23")).isNotEqualTo(RequestHash.of("OP", "12", "3"));
		assertThat(RequestHash.of("OP", "a;", "b")).isNotEqualTo(RequestHash.of("OP", "a", ";b"));
	}

}
