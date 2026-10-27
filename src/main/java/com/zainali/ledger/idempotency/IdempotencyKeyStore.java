package com.zainali.ledger.idempotency;

import java.util.Optional;

/** Storage for idempotency keys. Every method runs in the caller's transaction. */
interface IdempotencyKeyStore {

	/**
	 * Inserts the key unless {@code (ownerId, key)} already exists. Returns {@code true} if this call inserted it.
	 * If another transaction is inserting the same key, this waits until that transaction commits or rolls back.
	 */
	boolean insertIfAbsent(String ownerId, String key, String requestHash);

	Optional<StoredKey> find(String ownerId, String key);

	void storeResponse(String ownerId, String key, int status, String body);

}
