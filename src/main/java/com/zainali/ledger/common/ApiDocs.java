package com.zainali.ledger.common;

/**
 * Text and example payloads for the OpenAPI description. Annotation values must be compile-time constants,
 * so they live here and the controllers refer to them. The values are illustrative.
 */
public final class ApiDocs {

	public static final String TAG_ACCOUNTS = "Accounts";
	public static final String TAG_TRANSFERS = "Transfers";
	public static final String TAG_ADMIN = "Admin";

	public static final String IDEMPOTENCY_KEY_DESCRIPTION = "Required, at most 255 characters; a UUID works well. "
			+ "Repeating the same key with the same request returns the original response again, with the header "
			+ "`Idempotency-Replayed: true`. The same key with a different request is 422 `IDEMPOTENCY_KEY_REUSED`. "
			+ "A failed request stores nothing under its key, so a retry with the same key runs again "
			+ "(for example after `INSUFFICIENT_FUNDS`, once the account has been funded). "
			+ "Keys are scoped to the caller.";

	public static final String IDEMPOTENCY_KEY_EXAMPLE = "7b0c3a52-4f6e-4a43-9d3e-6a1d2f0b9c11";

	public static final String ACCOUNT_RESPONSE = """
			{"id": 42, "ownerId": "5f2b1c6e-8a0d-4c55-9d52-0a2d3b6f7e10", "type": "CUSTOMER", "currency": "EUR", \
			"balance": 12500, "createdAt": "2026-11-15T10:15:30Z"}""";

	public static final String ACCOUNT_LIST_RESPONSE = "[" + ACCOUNT_RESPONSE + "]";

	public static final String CREATE_ACCOUNT_REQUEST = """
			{"currency": "EUR"}""";

	public static final String CREATE_ACCOUNT_FOR_OWNER_REQUEST = """
			{"currency": "USD", "ownerId": "5f2b1c6e-8a0d-4c55-9d52-0a2d3b6f7e10"}""";

	public static final String BALANCE_RESPONSE = """
			{"accountId": 42, "currency": "EUR", "balance": 12500}""";

	public static final String HISTORY_RESPONSE = """
			{"content": [{"id": 311, "transferId": 118, "direction": "DEBIT", "amount": 2500, "currency": "EUR", \
			"createdAt": "2026-11-15T10:20:00Z"}, {"id": 305, "transferId": 112, "direction": "CREDIT", \
			"amount": 15000, "currency": "EUR", "createdAt": "2026-11-15T10:16:00Z"}], \
			"page": 0, "size": 20, "totalElements": 2, "totalPages": 1}""";

	public static final String DEPOSIT_REQUEST = """
			{"amount": 15000, "currency": "EUR"}""";

	public static final String DEPOSIT_RESPONSE = """
			{"id": 112, "type": "DEPOSIT", "status": "COMPLETED", "sourceAccountId": 1, "targetAccountId": 42, \
			"amount": 15000, "currency": "EUR", "reversesTransferId": null, "createdAt": "2026-11-15T10:16:00Z"}""";

	public static final String TRANSFER_REQUEST = """
			{"sourceAccountId": 42, "targetAccountId": 43, "amount": 2500, "currency": "EUR"}""";

	public static final String TRANSFER_RESPONSE = """
			{"id": 118, "type": "TRANSFER", "status": "COMPLETED", "sourceAccountId": 42, "targetAccountId": 43, \
			"amount": 2500, "currency": "EUR", "reversesTransferId": null, "createdAt": "2026-11-15T10:20:00Z"}""";

	public static final String REVERSAL_RESPONSE = """
			{"id": 119, "type": "REVERSAL", "status": "COMPLETED", "sourceAccountId": 43, "targetAccountId": 42, \
			"amount": 2500, "currency": "EUR", "reversesTransferId": 118, "createdAt": "2026-11-15T10:25:00Z"}""";

	public static final String RECONCILIATION_RESPONSE = """
			{"checkedAt": "2026-11-15T11:00:00Z", "consistent": true, "truncated": false, \
			"transferImbalances": [], "balanceMismatches": []}""";

	public static final String RECONCILIATION_MISMATCH_RESPONSE = """
			{"checkedAt": "2026-11-15T11:00:00Z", "consistent": false, "truncated": false, \
			"transferImbalances": [], \
			"balanceMismatches": [{"accountId": 42, "cachedBalance": 12600, "entriesSum": 12500}]}""";

	private ApiDocs() {
	}

}
