package com.zainali.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotency and the row-lock reads rely on READ COMMITTED (DECISIONS 9), so the write paths pin it explicitly rather
 * than inheriting whatever the datasource default happens to be.
 */
class TransactionSettingsTests {

	private static Transactional transactionalOn(Class<?> type, String method) {
		Method found = Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getName().equals(method)).findFirst()
				.orElseThrow();
		return found.getAnnotation(Transactional.class);
	}

	@Test
	void depositPinsReadCommitted() {
		assertThat(transactionalOn(DepositService.class, "deposit").isolation()).isEqualTo(Isolation.READ_COMMITTED);
	}

	@Test
	void transferPinsReadCommitted() {
		assertThat(transactionalOn(TransferService.class, "transfer").isolation()).isEqualTo(Isolation.READ_COMMITTED);
	}

	@Test
	void reversalPinsReadCommitted() {
		assertThat(transactionalOn(ReversalService.class, "reverse").isolation()).isEqualTo(Isolation.READ_COMMITTED);
	}

}
