# Plan

Base package: `com.zainali.ledger`. Stack: Java 21, Spring Boot 4.1.x (re-verify the exact version at scaffold time), PostgreSQL, Flyway, Keycloak, Maven.
Tick each milestone when it is finished and its tests pass. One commit per milestone.

## Rules the milestones must respect
- Account types: `CUSTOMER` and `EXTERNAL`. One `EXTERNAL` account per currency, which may go negative. Only the deposit endpoint may touch it; `POST /transfers` rejects an `EXTERNAL` source or target (`EXTERNAL_ACCOUNT_NOT_ALLOWED`).
- A transfer can be sent by the owner of the source account, or by an ADMIN between any two accounts. Same-account and currency-mismatch transfers are rejected.
- Idempotency is safe under concurrent requests with the same key. The key row is inserted first, under the unique constraint. Failed requests are never stored.
- Only a `COMPLETED` transfer of type `TRANSFER` can be reversed, and only once. Reversals and deposits cannot be reversed. If the original recipient lacks funds, the reversal fails with `INSUFFICIENT_FUNDS`.
- Balances are cached in `accounts.balance`, updated in the same transaction as the entries. Entries are the source of truth.

## Milestones
- [x] 1. Scaffold (Boot 4.1.2; `./mvnw clean verify` passes, 2 tests): Maven wrapper, Boot 4.1.x skeleton, package-by-feature packages, Docker Compose (Postgres + Keycloak), profiles `local` and `test`, health endpoint, one passing context test. Update the commands in CLAUDE.md.
- [x] 2. Schema (V1-V5 migrations; `./mvnw clean verify` passes, 20 tests): Flyway migrations for `accounts`, `transfers`, `ledger_entries`, `idempotency_keys`, with constraints, indexes, and a seeded `EXTERNAL` account per supported currency (EUR, USD). Testcontainers test that the migrations apply and the constraints hold.
- [x] 3. CI (`.github/workflows/ci.yml`; passes on GitHub, 20 tests): GitHub Actions workflow running `./mvnw verify` on push and pull request, with Maven cache.
- [x] 4. Security (`keycloak/ledger-realm.json`, `SecurityConfig`; `./mvnw clean verify` passes, 33 tests): OAuth2 resource server with Keycloak JWTs, realm export with roles USER and ADMIN and test users, tests for 401, 403 and 200.
- [x] 5. Accounts (`AccountController`, `ApiExceptionHandler`; `./mvnw clean verify` passes, 68 tests): `POST /accounts`, `GET /accounts/{id}`, `GET /accounts`, DTOs, validation, the single `@ControllerAdvice` with stable error codes, ownership rules, unit and integration tests.
- [ ] 6. Deposits and transfers: `POST /accounts/{id}/deposits` (ADMIN), `POST /transfers`, `GET /transfers/{id}`, double-entry in one transaction, idempotency, all error codes, duplicate-request and concurrent-same-key tests.
- [ ] 7. Concurrency: lock both accounts with `SELECT ... FOR UPDATE` in ascending id order, a test with 50 concurrent transfers on one account checking that no money is created or lost, a decision entry.
- [ ] 8. Balance and history: `GET /accounts/{id}/balance`, `GET /accounts/{id}/transactions` with pagination, sorting and date range, an index migration, tests for empty accounts and large page numbers.
- [ ] 9. Reversals: `POST /transfers/{id}/reverse` (ADMIN), compensating entries, one reversal per transfer enforced by a unique constraint, tests for the normal case, double reversal, reversing a reversal, reversing a deposit, missing transfer, and an insufficient-funds recipient.
- [ ] 10. Reconciliation: scheduled job and `POST /admin/reconciliation`, checks that debits equal credits and each balance equals the sum of its entries, a test that corrupts data and checks detection.
- [ ] 11. Observability: Actuator, Micrometer metrics (transfer count, failures, latency), structured JSON logs with a correlation ID, Prometheus and Grafana in Compose with a basic dashboard.
- [ ] 12. API docs: springdoc-openapi with examples, error responses and the security scheme, plus a `.http` file for every endpoint.
- [ ] 13. Hardening review: findings by severity, high and medium fixed one commit at a time.
- [ ] 14. Coverage: JaCoCo, tests for the most important untested paths, a summary in CI output.
- [ ] 15. Containerize: multi-stage Dockerfile with a non-root user, one command starts app, Postgres, Keycloak, Prometheus and Grafana.
- [ ] 16. README and final clone-and-run audit.

## Dependencies to ask about before adding
springdoc-openapi (milestone 12), JaCoCo (milestone 14), and a JSON logging library only if Boot's built-in structured logging is not enough (milestone 11).

## Endpoints
| Method | Path | Role |
|---|---|---|
| GET | /actuator/health | public |
| POST | /accounts | USER, ADMIN (ADMIN may set the owner) |
| GET | /accounts | owner, ADMIN sees all |
| GET | /accounts/{id} | owner, ADMIN |
| POST | /accounts/{id}/deposits | ADMIN |
| POST | /transfers | owner of source, ADMIN |
| GET | /transfers/{id} | a party to the transfer, ADMIN |
| GET | /accounts/{id}/balance | owner, ADMIN |
| GET | /accounts/{id}/transactions | owner, ADMIN |
| POST | /transfers/{id}/reverse | ADMIN |
| POST | /admin/reconciliation | ADMIN |

Deposits, transfers and reversals all require an `Idempotency-Key` header.

## Error codes
`VALIDATION_FAILED`, `NOT_FOUND`, `INSUFFICIENT_FUNDS`, `SAME_ACCOUNT`, `CURRENCY_MISMATCH`, `EXTERNAL_ACCOUNT_NOT_ALLOWED`, `IDEMPOTENCY_KEY_REQUIRED`, `IDEMPOTENCY_KEY_REUSED`, `NOT_REVERSIBLE`, `ALREADY_REVERSED`.

## Package layout
```
com.zainali.ledger
  account/         controller, service, entity, repository, dto
  transfer/        controller, service, entity, repository, dto
  ledger/          ledger entry, balance and history queries
  idempotency/     idempotency key handling
  reconciliation/  job, service, admin controller
  common/          error handling, security config, money type, web filters
```

## Hardening backlog
- Reject unknown request fields (for example a `type` sent to `POST /accounts` is currently ignored).
- 401 and 403 from the security filter chain should use the same problem-response format as the `@ControllerAdvice`.
- Paginate `GET /accounts`.
- Validate the JWT audience.
- Add `FORBIDDEN` and `INTERNAL_ERROR` to the error-code list.
- A transfer to a nonexistent target returns 404, so callers can probe which account ids exist. Uniform errors or UUID ids would fix it.
