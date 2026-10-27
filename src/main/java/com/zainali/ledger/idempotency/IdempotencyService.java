package com.zainali.ledger.idempotency;

import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.zainali.ledger.common.ErrorCode;
import com.zainali.ledger.common.LedgerException;

import tools.jackson.databind.json.JsonMapper;

/**
 * Runs an operation at most once per {@code (owner, Idempotency-Key)} (DECISIONS 9).
 * <p>
 * The key row is inserted first, in the same transaction as the work. Concurrent duplicates queue on the unique index:
 * the loser either replays the winner's stored response, or, if the winner failed and rolled back, runs the work itself.
 * A failed request rolls back with its key row, so failures are never stored.
 */
@Service
public class IdempotencyService {

	static final int MAX_KEY_LENGTH = 255;

	private final IdempotencyKeyStore store;
	private final JsonMapper json;

	IdempotencyService(IdempotencyKeyStore store, JsonMapper json) {
		this.store = store;
		this.json = json;
	}

	/**
	 * @param requestHash fingerprint of the request; see {@link RequestHash}
	 * @param successStatus the HTTP status stored with, and replayed for, a successful response
	 * @param work the operation. It runs in the caller's transaction, and its result must serialise to JSON.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public <T> IdempotentResult<T> execute(String ownerId, String key, String requestHash, Class<T> responseType,
			int successStatus, Supplier<T> work) {
		validateKey(key);
		if (!store.insertIfAbsent(ownerId, key, requestHash)) {
			return replay(ownerId, key, requestHash, responseType);
		}
		T body = work.get();
		store.storeResponse(ownerId, key, successStatus, json.writeValueAsString(body));
		return new IdempotentResult<>(successStatus, body, false);
	}

	private <T> IdempotentResult<T> replay(String ownerId, String key, String requestHash, Class<T> responseType) {
		StoredKey stored = store.find(ownerId, key)
				.orElseThrow(() -> new IllegalStateException("Idempotency key disappeared after a conflict"));
		if (!stored.requestHash().equals(requestHash)) {
			throw new LedgerException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
					"This Idempotency-Key was already used for a different request");
		}
		if (stored.responseStatus() == null) {
			throw new IllegalStateException("Idempotency key has no stored response");
		}
		return new IdempotentResult<>(stored.responseStatus(), json.readValue(stored.responseBody(), responseType), true);
	}

	private static void validateKey(String key) {
		if (key == null || key.isBlank()) {
			throw new LedgerException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, "The Idempotency-Key header is required");
		}
		if (key.length() > MAX_KEY_LENGTH) {
			throw new LedgerException(ErrorCode.VALIDATION_FAILED,
					"The Idempotency-Key header must be at most " + MAX_KEY_LENGTH + " characters");
		}
	}

}
