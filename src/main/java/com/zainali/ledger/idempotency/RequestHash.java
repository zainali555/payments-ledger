package com.zainali.ledger.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Fingerprint of a request: the operation plus its parsed parameters, not the raw body, so formatting differences in
 * the JSON do not matter. The fields are length-prefixed, so ("1", "23") and ("12", "3") hash differently.
 */
public final class RequestHash {

	private RequestHash() {
	}

	/** SHA-256 as 64 lowercase hex characters, which is the width of {@code idempotency_keys.request_hash}. */
	public static String of(String operation, Object... parts) {
		StringBuilder canonical = new StringBuilder();
		append(canonical, operation);
		for (Object part : parts) {
			append(canonical, String.valueOf(part));
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is always available", ex);
		}
	}

	private static void append(StringBuilder canonical, String value) {
		canonical.append(value.length()).append(':').append(value).append(';');
	}

}
