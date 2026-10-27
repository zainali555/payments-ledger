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

Balances are integer minor units (cents). Errors are RFC 7807 problem responses with a stable `code`, for example `VALIDATION_FAILED`, `NOT_FOUND`, `FORBIDDEN`.
