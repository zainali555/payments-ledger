# Design decisions

One entry per decision: what, why, and what else was considered.

## 1. Modular monolith, package by feature
- **Decision:** One Spring Boot app, packages `account`, `transfer`, `ledger`, `idempotency`, `reconciliation`, `common` under `com.zainali.ledger`.
- **Reason:** A ledger needs one atomic transaction across accounts, entries and idempotency keys. Splitting into services would turn that into a distributed transaction.
- **Alternatives:** Microservices (rejected: no atomic writes). Package by layer (rejected: scatters one feature across the tree).

## 2. Spring Boot 4.1.x on Java 21
- **Decision:** Use the latest stable Boot 4.x (spring.io currently lists 4.1.2; re-check at scaffold time) on Java 21.
- **Reason:** Boot 3.5 reached the end of open-source support on 30 June 2026 and 3.5 was the last 3.x release.
- **Boot 4 starter changes to apply:**
  - `spring-boot-starter-webmvc` replaces `spring-boot-starter-web`.
  - `spring-boot-starter-security-oauth2-resource-server` replaces `spring-boot-starter-oauth2-resource-server`.
  - Flyway needs `spring-boot-starter-flyway`; the library alone no longer auto-configures.
  - Test starters are per technology, e.g. `spring-boot-starter-webmvc-test` and `spring-boot-starter-data-jpa-test`.
  - Jackson 3 is the default (`tools.jackson` group, except `jackson-annotations`). Use a `JsonMapper` bean, not an `ObjectMapper` bean, to customise it.
  - `@MockBean` and `@SpyBean` are gone; use `@MockitoBean` and `@MockitoSpyBean`.
- **Alternatives:** Stay on 3.5 (rejected: unsupported). Boot 4.0.x (rejected: its open-source support ends sooner than 4.1.x).

## 3. Money as integer minor units plus currency
- **Decision:** `BIGINT` amounts in minor units and a 3-letter currency code. One currency per account, no FX; a currency mismatch is rejected.
- **Reason:** Exact arithmetic, no rounding errors.
- **Alternatives:** `NUMERIC` (works, but easy to misuse in Java). `double` (never).

## 4. Double-entry with positive amounts and a direction
- **Decision:** Each entry has a positive `amount` and a `DEBIT` or `CREDIT` direction. Every transfer writes one debit and one credit of the same amount, so signed entries sum to zero.
- **Reason:** Satisfies the "amount > 0" check and the "sums to zero" rule together.
- **Alternatives:** Signed amounts (rejected: loses the positive-amount check).

## 5. Cached balance column, entries as source of truth
- **Decision:** `accounts.balance` is updated in the same transaction as the entries, under the row lock. `CHECK (balance >= 0 OR type = 'EXTERNAL')`. Reconciliation verifies that the column equals the sum of entries.
- **Reason:** Cheap insufficient-funds check, and a real invariant for the reconciliation job to verify.
- **Alternatives:** Compute the balance from entries only (simpler, but needs an aggregate under a lock on every transfer).

## 6. EXTERNAL account for deposits, closed to transfers
- **Decision:** One `EXTERNAL` account per currency may go negative and is the source of every deposit. `POST /transfers` rejects an `EXTERNAL` source or target with `EXTERNAL_ACCOUNT_NOT_ALLOWED`; only the ADMIN deposit endpoint may use it.
- **Reason:** Money enters the system through double-entry, and users cannot move money out of, or into, the funding account.
- **Alternatives:** Seed balances (not usable through the API). A single account for all currencies (breaks one currency per account).

## 7. Transfer permissions
- **Decision:** A USER may send from accounts they own to any account. An ADMIN may send between any two accounts.
- **Reason:** Matches how payments work; the sender authorises the debit.
- **Alternatives:** Own accounts only (too restrictive).

## 8. Pessimistic locking in a fixed order
- **Decision:** Lock both accounts with `SELECT ... FOR UPDATE` in ascending id order.
- **Reason:** A consistent order prevents deadlocks, and there is no retry logic to get wrong. Final rationale is recorded when milestone 7 is built.
- **Alternatives:** Optimistic locking with retry (more retries under contention on hot accounts).

## 9. Idempotency safe under concurrent requests
- **Decision:** In the same transaction as the work, first run `INSERT INTO idempotency_keys ... ON CONFLICT DO NOTHING` on the unique `(owner_id, key)`.
  - If the insert wins, continue, then store the response on the row and commit.
  - If it affects zero rows, the other request holds or has committed the key (Postgres makes the insert wait for the other transaction). Read the stored row after that: same request hash replays the stored response, a different hash returns `IDEMPOTENCY_KEY_REUSED` (422).
  - Failed requests roll back, which also removes the key row. A retry with the same key runs again. Failures are never stored.
- **Reason:** The unique index serialises concurrent duplicates, so there is no check-then-insert race. `ON CONFLICT DO NOTHING` avoids a unique-violation error that would abort the Postgres transaction.
- **Alternatives:** Select first, then insert (racy). Catch the constraint violation (aborts the transaction in Postgres). Advisory locks (extra moving parts).

## 10. Reversal rules
- **Decision:**
  - Only a `COMPLETED` transfer of type `TRANSFER` can be reversed. Deposits and reversals cannot be (`NOT_REVERSIBLE`).
  - A reversal is a new `REVERSAL` transfer with the original's accounts swapped, plus two new entries. Nothing is edited or deleted, except that the original's `status` changes to `REVERSED`.
  - `transfers.reverses_transfer_id` is unique, so a second reversal fails even in a race (`ALREADY_REVERSED`).
  - If the original recipient lacks funds, the reversal fails with `INSUFFICIENT_FUNDS`.
- **Reason:** Keeps entries append-only, and the database enforces "once only".
- **Alternatives:** Let the recipient go negative (rejected: breaks the non-negative balance rule). Reversing deposits (rejected: would need an EXTERNAL-account workflow).

## 11. Errors as RFC 7807 with stable codes
- **Decision:** A single `@ControllerAdvice` returns `ProblemDetail` with a stable `code` field.
- **Reason:** Clients can branch on the code and not on message text.
- **Alternatives:** A custom error envelope (no standard tooling).
