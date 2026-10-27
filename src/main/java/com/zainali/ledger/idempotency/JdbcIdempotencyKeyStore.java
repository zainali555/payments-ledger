package com.zainali.ledger.idempotency;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Plain SQL on purpose: {@code ON CONFLICT DO NOTHING} is what lets concurrent duplicates queue on the unique index
 * without a constraint violation aborting the Postgres transaction (DECISIONS 9).
 */
@Repository
class JdbcIdempotencyKeyStore implements IdempotencyKeyStore {

	private final JdbcClient jdbc;

	JdbcIdempotencyKeyStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public boolean insertIfAbsent(String ownerId, String key, String requestHash) {
		int inserted = jdbc.sql("""
				INSERT INTO idempotency_keys (owner_id, key, request_hash)
				VALUES (:ownerId, :key, :requestHash)
				ON CONFLICT (owner_id, key) DO NOTHING
				""")
				.param("ownerId", ownerId)
				.param("key", key)
				.param("requestHash", requestHash)
				.update();
		return inserted == 1;
	}

	@Override
	public Optional<StoredKey> find(String ownerId, String key) {
		return jdbc.sql("""
				SELECT request_hash, response_status, response_body
				FROM idempotency_keys
				WHERE owner_id = :ownerId AND key = :key
				""")
				.param("ownerId", ownerId)
				.param("key", key)
				.query((rs, rowNum) -> new StoredKey(rs.getString("request_hash"),
						(Integer) rs.getObject("response_status"), rs.getString("response_body")))
				.optional();
	}

	@Override
	public void storeResponse(String ownerId, String key, int status, String body) {
		jdbc.sql("""
				UPDATE idempotency_keys
				SET response_status = :status, response_body = :body
				WHERE owner_id = :ownerId AND key = :key
				""")
				.param("status", status)
				.param("body", body)
				.param("ownerId", ownerId)
				.param("key", key)
				.update();
	}

}
