# Payments Ledger Service

A double-entry payments ledger built with Java 21 and Spring Boot 4.

Status: work in progress. Full documentation will be added at the end of the project.

## API (so far)
All endpoints except `GET /actuator/health` need a Keycloak bearer token with the `USER` or `ADMIN` realm role.

| Method | Path | Notes |
|---|---|---|
| POST | `/accounts` | Body `{"currency": "EUR" \| "USD", "ownerId": optional}`. Creates a `CUSTOMER` account owned by the caller (only an ADMIN may set another `ownerId`). Returns 201 with a `Location` header. |
| GET | `/accounts/{id}` | Owner or ADMIN. Returns 404 if the account does not exist or is not yours. |
| GET | `/accounts` | Your accounts; an ADMIN sees all. |
| GET | `/accounts/{id}/balance` | Same visibility as `GET /accounts/{id}` (404 if missing or not yours). Returns `{"accountId", "currency", "balance": minor units}`, the cached balance, which always equals the sum of the account's ledger entries. |
| GET | `/accounts/{id}/transactions` | Same visibility (404). The account's ledger entries, as a page `{"content": [{"id", "transferId", "direction": "DEBIT" \| "CREDIT", "amount", "currency", "createdAt"}], "page", "size", "totalElements", "totalPages"}`. Query: `page` (from 0, default 0), `size` (default 20, a larger value is lowered to 100, below 1 is 400), `sort` = `createdAt` or `amount`, optionally `,asc` or `,desc` (default `createdAt,desc`; a bare field means ascending), `from` (inclusive) and `to` (exclusive) as ISO-8601 instants such as `2026-01-31T00:00:00Z`. A page past the end is empty with the real totals. Bad `page`, `size`, `sort` or dates, or `from` after `to`, are 400 `VALIDATION_FAILED`. |
| POST | `/accounts/{id}/deposits` | ADMIN only. Header `Idempotency-Key` (required, max 255 characters). Body `{"amount": minor units, 1 to 10^12, "currency": "EUR" \| "USD"}`. Moves money from the currency's `EXTERNAL` account to the account. Returns 201 with the transfer. Repeating the same key and body returns the original response with `Idempotency-Replayed: true`; the same key with a different body returns 422 `IDEMPOTENCY_KEY_REUSED`. |
| POST | `/transfers` | The owner of the source account, or an ADMIN. Header `Idempotency-Key` (required, max 255 characters). Body `{"sourceAccountId", "targetAccountId", "amount": minor units, 1 to 10^12, "currency": "EUR" \| "USD"}`. Returns 201 with the transfer and a `Location` header. A source you do not own is 404. 422 for `SAME_ACCOUNT`, `INSUFFICIENT_FUNDS`, `CURRENCY_MISMATCH` and `EXTERNAL_ACCOUNT_NOT_ALLOWED`. A repeated key and body returns the original response with `Idempotency-Replayed: true`; the same key with a different body returns 422 `IDEMPOTENCY_KEY_REUSED`. |
| GET | `/transfers/{id}` | A party to the transfer (owner of its source or target account) or an ADMIN. Anyone else gets 404. |
| POST | `/transfers/{id}/reverse` | ADMIN only. Header `Idempotency-Key` (required, max 255 characters), no body. Creates a `REVERSAL` transfer that moves the amount back from the original recipient to the original sender, and sets the original's `status` to `REVERSED`. Returns 201 with the reversal (its `reversesTransferId` is the original's id) and a `Location` header pointing at it. A missing transfer is 404. 422 `NOT_REVERSIBLE` for deposits and reversals, `ALREADY_REVERSED` if the transfer was already reversed (only one reversal ever succeeds, even under concurrent requests), `INSUFFICIENT_FUNDS` if the original recipient no longer holds the amount. Replays work as for transfers. |

Transfer responses have the fields `id`, `type` (`TRANSFER`, `DEPOSIT` or `REVERSAL`), `status` (`COMPLETED` or `REVERSED`), `sourceAccountId`, `targetAccountId`, `amount`, `currency`, `reversesTransferId` (null except on a reversal) and `createdAt`.

## API docs
Interactive docs (Swagger UI) are at <http://localhost:8080/swagger-ui.html> and the OpenAPI description at <http://localhost:8080/v3/api-docs>. Both are public (no token) so the page can load; click **Authorize** and paste a Keycloak `access_token` to try the endpoints. The description documents every endpoint with request and response examples, every error response (RFC 7807 problem documents with a stable `code`), the `Idempotency-Key` header and the bearer scheme. In production, disable or restrict them (`springdoc.api-docs.enabled=false`, `springdoc.swagger-ui.enabled=false`; DECISIONS 20).

`api.http` in the project root has one example call per endpoint, including how to get a token for the local dev users and reuse it (VS Code REST Client).

A failed request stores nothing under its `Idempotency-Key`: it rolls back completely, so a retry with the same key runs again (for example an `INSUFFICIENT_FUNDS` transfer can succeed once the account has been funded). Only a successful request is replayed.

Balances are integer minor units (cents). Errors are RFC 7807 problem responses with a stable `code`, for example `VALIDATION_FAILED`, `NOT_FOUND`, `FORBIDDEN`, `IDEMPOTENCY_KEY_REQUIRED`, `IDEMPOTENCY_KEY_REUSED`, `INSUFFICIENT_FUNDS`, `SAME_ACCOUNT`, `CURRENCY_MISMATCH`, `EXTERNAL_ACCOUNT_NOT_ALLOWED`, `NOT_REVERSIBLE`, `ALREADY_REVERSED`.
