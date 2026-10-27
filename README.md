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
| POST | `/accounts/{id}/deposits` | ADMIN only. Header `Idempotency-Key` (required, max 255 characters). Body `{"amount": minor units, 1 to 10^12, "currency": "EUR" \| "USD"}`. Moves money from the currency's `EXTERNAL` account to the account. Returns 201 with the transfer. Repeating the same key and body returns the original response with `Idempotency-Replayed: true`; the same key with a different body returns 422 `IDEMPOTENCY_KEY_REUSED`. |

Balances are integer minor units (cents). Errors are RFC 7807 problem responses with a stable `code`, for example `VALIDATION_FAILED`, `NOT_FOUND`, `FORBIDDEN`, `IDEMPOTENCY_KEY_REQUIRED`, `IDEMPOTENCY_KEY_REUSED`, `CURRENCY_MISMATCH`, `EXTERNAL_ACCOUNT_NOT_ALLOWED`.
