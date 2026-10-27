package com.zainali.ledger.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.zainali.ledger.common.ErrorCode;
import com.zainali.ledger.common.LedgerException;

import tools.jackson.databind.json.JsonMapper;

class IdempotencyServiceTests {

	record Payload(long id, String name) {
	}

	private final IdempotencyKeyStore store = mock(IdempotencyKeyStore.class);
	private final JsonMapper json = JsonMapper.builder().build();
	private final IdempotencyService service = new IdempotencyService(store, json);

	private static final String OWNER = "admin-1";
	private static final String KEY = "key-1";
	private static final String HASH = "a".repeat(64);

	// --- first request ---

	@Test
	void firstRequestRunsTheWorkAndStoresTheResponse() {
		when(store.insertIfAbsent(OWNER, KEY, HASH)).thenReturn(true);

		IdempotentResult<Payload> result = service.execute(OWNER, KEY, HASH, Payload.class, 201,
				() -> new Payload(7, "done"));

		assertThat(result.replayed()).isFalse();
		assertThat(result.status()).isEqualTo(201);
		assertThat(result.body()).isEqualTo(new Payload(7, "done"));
		verify(store).storeResponse(OWNER, KEY, 201, "{\"id\":7,\"name\":\"done\"}");
	}

	@Test
	void keyIsClaimedBeforeTheWorkRuns() {
		when(store.insertIfAbsent(OWNER, KEY, HASH)).thenReturn(true);
		AtomicInteger claimsSeenByWork = new AtomicInteger();

		service.execute(OWNER, KEY, HASH, Payload.class, 201, () -> {
			// The insert must already have happened when the work starts.
			verify(store).insertIfAbsent(OWNER, KEY, HASH);
			claimsSeenByWork.incrementAndGet();
			return new Payload(1, "x");
		});

		assertThat(claimsSeenByWork).hasValue(1);
	}

	@Test
	void failingWorkPropagatesAndStoresNothing() {
		when(store.insertIfAbsent(OWNER, KEY, HASH)).thenReturn(true);
		LedgerException failure = new LedgerException(ErrorCode.INSUFFICIENT_FUNDS, "no money");

		assertThatThrownBy(() -> service.execute(OWNER, KEY, HASH, Payload.class, 201, () -> {
			throw failure;
		})).isSameAs(failure);

		verify(store, never()).storeResponse(anyString(), anyString(), anyInt(), anyString());
	}

	// --- duplicate request ---

	@Test
	void duplicateWithSameHashReplaysTheStoredResponseWithoutRunningTheWork() {
		when(store.insertIfAbsent(OWNER, KEY, HASH)).thenReturn(false);
		when(store.find(OWNER, KEY)).thenReturn(Optional.of(new StoredKey(HASH, 201, "{\"id\":7,\"name\":\"done\"}")));
		AtomicInteger runs = new AtomicInteger();

		IdempotentResult<Payload> result = service.execute(OWNER, KEY, HASH, Payload.class, 201, () -> {
			runs.incrementAndGet();
			return new Payload(8, "second");
		});

		assertThat(runs).hasValue(0);
		assertThat(result.replayed()).isTrue();
		assertThat(result.status()).isEqualTo(201);
		assertThat(result.body()).isEqualTo(new Payload(7, "done"));
		verify(store, never()).storeResponse(anyString(), anyString(), anyInt(), anyString());
	}

	@Test
	void replayUsesTheStoredStatusNotTheCurrentOne() {
		when(store.insertIfAbsent(OWNER, KEY, HASH)).thenReturn(false);
		when(store.find(OWNER, KEY)).thenReturn(Optional.of(new StoredKey(HASH, 200, "{\"id\":1,\"name\":\"n\"}")));

		IdempotentResult<Payload> result = service.execute(OWNER, KEY, HASH, Payload.class, 201,
				() -> new Payload(0, ""));

		assertThat(result.status()).isEqualTo(200);
	}

	@Test
	void duplicateWithDifferentHashIsRejectedAsKeyReused() {
		when(store.insertIfAbsent(OWNER, KEY, HASH)).thenReturn(false);
		when(store.find(OWNER, KEY)).thenReturn(Optional.of(new StoredKey("b".repeat(64), 201, "{}")));
		AtomicInteger runs = new AtomicInteger();

		assertThatThrownBy(() -> service.execute(OWNER, KEY, HASH, Payload.class, 201, () -> {
			runs.incrementAndGet();
			return new Payload(0, "");
		})).isInstanceOfSatisfying(LedgerException.class,
				ex -> assertThat(ex.code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));

		assertThat(runs).hasValue(0);
	}

	@Test
	void keyWithoutStoredResponseIsAnInternalError() {
		when(store.insertIfAbsent(OWNER, KEY, HASH)).thenReturn(false);
		when(store.find(OWNER, KEY)).thenReturn(Optional.of(new StoredKey(HASH, null, null)));

		assertThatThrownBy(() -> service.execute(OWNER, KEY, HASH, Payload.class, 201, () -> new Payload(0, "")))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void keyThatVanishedAfterAConflictIsAnInternalError() {
		when(store.insertIfAbsent(OWNER, KEY, HASH)).thenReturn(false);
		when(store.find(OWNER, KEY)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.execute(OWNER, KEY, HASH, Payload.class, 201, () -> new Payload(0, "")))
				.isInstanceOf(IllegalStateException.class);
	}

	// --- key validation ---

	@Test
	void nullKeyIsRequired() {
		assertKeyRejected(null, ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
	}

	@Test
	void emptyKeyIsRequired() {
		assertKeyRejected("", ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
	}

	@Test
	void blankKeyIsRequired() {
		assertKeyRejected("   ", ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
	}

	@Test
	void keyLongerThan255CharactersIsRejectedAsValidationFailure() {
		assertKeyRejected("k".repeat(256), ErrorCode.VALIDATION_FAILED);
	}

	@Test
	void keyOfExactly255CharactersIsAccepted() {
		String key = "k".repeat(255);
		when(store.insertIfAbsent(OWNER, key, HASH)).thenReturn(true);

		IdempotentResult<Payload> result = service.execute(OWNER, key, HASH, Payload.class, 201,
				() -> new Payload(1, "x"));

		assertThat(result.replayed()).isFalse();
	}

	private void assertKeyRejected(String key, ErrorCode expected) {
		assertThatThrownBy(() -> service.execute(OWNER, key, HASH, Payload.class, 201, () -> new Payload(0, "")))
				.isInstanceOfSatisfying(LedgerException.class, ex -> assertThat(ex.code()).isEqualTo(expected));
		verifyNoInteractions(store);
	}

}
