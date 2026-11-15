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
- **Decision (final, milestone 7):** Every transaction that changes balances locks all the accounts it touches with `SELECT ... FOR UPDATE` in ascending id order, before reading any balance, and holds the locks until commit. `AccountLocking.lockInIdOrder` is the only place account locks are taken; transfers and deposits both use it, and reversals (milestone 9) must too. Isolation stays `READ COMMITTED` (decision 9).
- **Global lock order:** within one transaction the idempotency key row comes first (the `INSERT ... ON CONFLICT` in decision 9), then accounts by ascending id. A transaction holds at most one key and takes it before any account lock, so a request waiting on a duplicate key holds no account locks, and there is no cycle between key and account locks.
- **Reason:**
  - **No deadlocks by construction.** A deadlock needs a cycle of transactions each waiting for a lock held by the next. If every transaction acquires locks in the same total order, the one holding the highest lock in any would-be cycle can always proceed, so no cycle forms. Without the order, A to B and B to A at the same time is the classic deadlock. Postgres would detect it after `deadlock_timeout` (1 s) and abort one side with SQLSTATE 40P01, which would surface as a 500 and need retry logic.
  - **Correct funds check.** The balance is read by the locking `SELECT`, so the insufficient-funds check and the `balance >= 0` constraint see the same value (decision 17).
  - **No retry logic.** Contention means waiting, not failing. Retries are easy to get wrong around idempotency, because a retried transaction must re-insert its key row.
  - **Ids give a natural order.** `BIGINT` identity ids (decision 13) are totally ordered and never change.
- **Evidence (`TransferConcurrencyIntegrationTests`, `AccountLockingTests`):**
  - 50 concurrent transfers on one hot account, 25 in and 25 out, with each counterpart sending to and receiving from it at once and counterpart ids on both sides of the hot id. The hot account cannot afford every outgoing transfer. Every response is 201 or `INSUFFICIENT_FUNDS`, the sum of the involved balances is unchanged, every balance equals its entry sum, and every transfer has one debit and one credit that sum to zero.
  - All 40 ordered pairs among five accounts (both directions twice) run at once. All succeed, and every balance ends where it started.
  - The unit tests pin ascending numeric order, de-duplication, and stopping at a missing account.
  - **Mutation check:** with the lock order changed to argument order, both integration tests fail with 500s, and the Postgres log shows `deadlock detected`. With ascending order they pass, so the tests detect the deadlocks the order prevents.
- **Costs accepted:**
  - Transfers on one account run one at a time, so a hot account's throughput is limited by one row lock. The `EXTERNAL` row is the hottest (decision 16).
  - A transfer to a missing target whose id is higher than the source's locks the source before failing with 404. This only blocks that source's other transfers briefly.
  - Lock waits are unbounded until the `lock_timeout` from the hardening backlog (M2) is added.
  - Any future code path that locks more than one account must go through `lockInIdOrder`, or the guarantee is lost.
- **Optimistic version as a backstop (review finding M1):** `accounts.version` (V6, JPA `@Version`) is bumped by every balance change. It is not the concurrency strategy, the row lock is. It makes a write based on a stale read fail with an optimistic-locking error instead of silently overwriting a newer balance, if some future code path updates an account without `FOR UPDATE`. There is no retry: such a failure means a bug, and it surfaces as `INTERNAL_ERROR`. Tested by loading a stale copy, committing a concurrent credit, then writing the stale copy (`AccountOptimisticLockingIntegrationTests`).
- **`credit` and `debit` stay public:** M1 suggested making them package-private. The callers are `TransferService` and `DepositService` in the `transfer` package, while `Account` lives in `account`, so package-private would not compile. Moving the money-moving logic into `account` would mean `AccountLocking` (or a new service) owning debit and credit; that is a larger refactor and was not done. The guards instead are: the methods require a positive amount, `debit` refuses an overdraft on non-EXTERNAL accounts, and the `@Version` column plus the `balance >= 0` constraint catch misuse. Revisited at milestone 9: `ReversalService` is the third caller and also lives in `transfer`, so the trade-off is unchanged and the methods stay public. All three callers take the accounts from `lockInIdOrder`.
- **Alternatives:**
  - *Optimistic locking (`@Version`) with retry as the main strategy* (rejected): on a hot account most concurrent attempts fail and retry, so throughput falls as contention grows. Each retry must also redo the idempotency-key insert. It is kept only as a backstop (above).
  - *Atomic conditional update* (`UPDATE accounts SET balance = balance - :amt WHERE id = :id AND balance >= :amt`, checking the row count; rejected): no explicit `SELECT`, and the funds check is in the statement. But the two `UPDATE`s still take row locks and still need a fixed order to avoid deadlocks. It also bypasses the JPA entity, the `@Version` backstop, and the domain checks in `Account.debit`. It gains little over `FOR UPDATE` here.
  - *`SERIALIZABLE` isolation with retry* (rejected): it needs a retry loop for serialization failures under contention, and it breaks the read-after-wait that idempotency relies on (decision 9).
  - *Lock in request order and retry on deadlock* (rejected): each deadlock costs at least `deadlock_timeout` (1 s) before Postgres aborts one side, and then a retry. It turns a problem that can be prevented into one that must be recovered from.
  - *`NOWAIT` or `SKIP LOCKED`* (rejected): it fails or skips immediately instead of waiting. That is wrong for payments, where a short wait is the expected behaviour. A bounded `lock_timeout` (M2) is the better limit.
  - *Advisory locks (`pg_advisory_xact_lock(id)`)* (rejected): they need the same ordering, they are not tied to the row, and nothing stops code from updating a row without taking the advisory lock first.
  - *A single-writer queue or one actor per account* (rejected): it serialises without database locks, but it adds infrastructure and only works in one process unless the queue is partitioned. That is too much for a modular monolith (decision 1).

## 9. Idempotency safe under concurrent requests
- **Decision:** In the same transaction as the work, first run `INSERT INTO idempotency_keys ... ON CONFLICT DO NOTHING` on the unique `(owner_id, key)`.
  - If the insert wins, continue, then store the response on the row and commit.
  - If it affects zero rows, the other request holds or has committed the key (Postgres makes the insert wait for the other transaction). Read the stored row after that: same request hash replays the stored response, a different hash returns `IDEMPOTENCY_KEY_REUSED` (422).
  - Failed requests roll back, which also removes the key row. A retry with the same key runs again. Failures are never stored.
- **Reason:** The unique index serialises concurrent duplicates, so there is no check-then-insert race. `ON CONFLICT DO NOTHING` avoids a unique-violation error that would abort the Postgres transaction.
- **Isolation level:** This relies on Postgres's default `READ COMMITTED`. After the insert waits for the other transaction to commit, the follow-up read of the key row must see that commit. Under `REPEATABLE READ` or `SERIALIZABLE` the snapshot is fixed at the first statement, so the read could miss the committed row (or fail with a serialization error), and the "key disappeared after a conflict" path would be reachable. The same holds for the row-lock reads in decisions 8 and 17. Do not raise the isolation level without revisiting this. `DepositService.deposit` and `TransferService.transfer` now pin `isolation = READ_COMMITTED` explicitly, so a changed datasource or database default cannot weaken or raise it silently (`TransactionSettingsTests`).
- **Alternatives:** Select first, then insert (racy). Catch the constraint violation (aborts the transaction in Postgres). Advisory locks (extra moving parts).

## 10. Reversal rules
- **Decision:**
  - Only a `COMPLETED` transfer of type `TRANSFER` can be reversed. Deposits and reversals cannot be (`NOT_REVERSIBLE`).
  - A reversal is a new `REVERSAL` transfer with the original's accounts swapped, plus two new entries. Nothing is edited or deleted, except that the original's `status` changes to `REVERSED`.
  - `transfers.reverses_transfer_id` is unique, so a second reversal fails even in a race (`ALREADY_REVERSED`).
  - If the original recipient lacks funds, the reversal fails with `INSUFFICIENT_FUNDS`.
- **Implementation (milestone 9, `ReversalService`):**
  - **Endpoint:** `POST /transfers/{id}/reverse`, ADMIN only at the filter chain (like deposits), `Idempotency-Key` required, no body. The request hash is `("REVERSE", id)` and keys are scoped per admin `sub`. Success is 201 with the new `REVERSAL` transfer and `Location: /transfers/{reversalId}`. `TransferResponse` gained `reversesTransferId` (null except on a reversal).
  - **Check order:** (1) read the original's type and account ids as plain values (`TransferRepository.findPartiesById`, a constructor projection, so no entity is cached); missing is `404 NOT_FOUND`; (2) a type other than `TRANSFER` is `NOT_REVERSIBLE`, before any lock, so reversing a deposit never locks the hot `EXTERNAL` row; (3) `lockInIdOrder(source, target)`; (4) load the original `FOR UPDATE` and reject a `REVERSED` status with `ALREADY_REVERSED`; (5) debit the original recipient (`INSUFFICIENT_FUNDS`), credit the original sender, insert the reversal, mark the original `REVERSED`, write the two entries.
  - **Lock order:** the idempotency key, then accounts by ascending id, then the transfer row. Nothing else locks transfer rows, so the global order of decision 8 still holds. Every reversal of a transfer locks the same two accounts first, so concurrent reversals of it run one at a time. Under `READ COMMITTED` the second one's `FOR UPDATE` read sees the `REVERSED` status the first committed. Type and account ids are read before the lock because they never change; the status is read after it because it does.
  - **Backstop:** if the locks were ever bypassed, the unique `transfers_reverses_uq` still rejects the second insert. `ReversalService` maps that violation (by constraint name) to `ALREADY_REVERSED` and not to a 500. Other integrity violations are rethrown.
  - **Statuses:** `NOT_REVERSIBLE` and `ALREADY_REVERSED` are 422, like the other rule violations (decision 16). A failed reversal stores nothing against its key, so after an `INSUFFICIENT_FUNDS` a retry with the same key can succeed once the recipient is funded again.
  - **Tests:** `ReversalServiceTests` (unit: check order, lock-before-load order, swapped entries, every rejection, the constraint mapping) and `ReversalApiIntegrationTests` (normal case, sequential and 8 concurrent reversals with different keys giving exactly one 201, concurrent same-key requests giving one reversal and replays, reversing a reversal and a deposit, a missing transfer, an insufficient-funds recipient then a successful retry, replay, key reuse, 401 and 403, and whole-database invariants: entries sum to zero per transfer, cached balances equal entry sums, every reversal mirrors a `REVERSED` original and every `REVERSED` transfer has its reversal).
- **Reason:** Keeps entries append-only, and the database enforces "once only".
- **Alternatives:** Let the recipient go negative (rejected: breaks the non-negative balance rule). Reversing deposits (rejected: would need an EXTERNAL-account workflow). Relying only on the unique constraint for concurrent reversals (rejected as the main guard: the loser would only fail at the insert, after debiting and crediting in memory; it is kept as the backstop). Locking the transfer row before the accounts (rejected: a new lock ahead of the accounts would change decision 8's global order for no gain, since the account locks already serialise reversals). 409 for `ALREADY_REVERSED` (rejected: 422 for consistency with decision 16).

## 11. Errors as RFC 7807 with stable codes
- **Decision:** A single `@ControllerAdvice` returns `ProblemDetail` with a stable `code` field.
- **Reason:** Clients can branch on the code and not on message text.
- **Alternatives:** A custom error envelope (no standard tooling).

## 12. Supported currencies: EUR and USD
- **Decision:** Only `EUR` and `USD` are supported. `accounts.currency` has a `CHECK (currency IN ('EUR', 'USD'))`, and V5 seeds one `EXTERNAL` account per currency (a partial unique index allows only one per currency). `transfers` and `ledger_entries` reference `accounts (id, currency)`, so a row's currency always equals its accounts' currency.
- **Reason:** Two currencies are enough to exercise the currency-mismatch rule without FX. The database enforces the list and the currency consistency, not only the application.
- **Adding a currency:** A new Flyway migration that replaces the check constraint and seeds its `EXTERNAL` account. Old migrations are never edited.
- **Alternatives:** A `currencies` table with a foreign key (more flexible, but more moving parts for a fixed list of two). Free-form ISO codes (rejected: no seeded `EXTERNAL` account for them).

## 13. Schema details
- **Decision:** Ids are `BIGINT` identity columns (ascending id order is needed for lock ordering, see 8). `EXTERNAL` accounts have no `owner_id`, `CUSTOMER` accounts must have one. `ledger_entries` has a unique `(transfer_id, direction)`, an index on `(account_id, created_at DESC, id DESC)` for history, and triggers that reject `UPDATE`, `DELETE` and `TRUNCATE`. `idempotency_keys` has a unique `(owner_id, key)` and nullable `response_status` and `response_body`, set together.
- **Reason:** The database enforces the append-only rule and the invariants from decisions 4, 5, 6 and 10, so a bug in the service layer cannot break them.
- **Alternatives:** UUID ids (no natural lock order). Enforcing append-only only in code (rejected: no safety net).
- **Trade-off:** Ids are sequential `BIGINT` because milestone 7 locks accounts in ascending id order; the cost is that account ids are guessable, so ownership checks on reads are mandatory, and UUIDs are listed as a "what I'd do next" item for the README.

## 14. Security: stateless resource server with Keycloak realm roles
- **Decision:** Every request needs a valid Keycloak JWT except `GET /actuator/health` and `/actuator/health/**`. Realm roles in `realm_access.roles` become `ROLE_USER` and `ROLE_ADMIN` (`KeycloakRealmRoleConverter`). `/admin/**` requires `ADMIN`; every other path requires authentication, and per-endpoint and ownership rules are added with each feature. Sessions, CSRF, form login and HTTP Basic are off. The issuer is `JWT_ISSUER_URI` (default `http://localhost:8081/realms/ledger`) and is resolved lazily, so the app and tests start without Keycloak.
- **Realm:** `keycloak/ledger-realm.json` is imported by Compose (`start-dev --import-realm`). It has realm roles `USER` and `ADMIN`, a public client `ledger-api` with the password grant for local curl and testing only, and users `alice` and `bob` (USER) and `admin-user` (ADMIN). The passwords are local-dev values in the file.
- **Reason:** Role checks stay in the API, and Keycloak only authenticates. Default-deny means a new endpoint is never public by accident. The service identifies callers by the JWT `sub` claim.
- **Tests:** `SecurityIntegrationTests` uses Spring Security's `jwt()` post-processor for 401, 403 and 200. One test sends real bearer tokens through a stub `JwtDecoder`, to cover the role mapping in the filter chain. Signature and issuer validation belong to Spring's decoder and were checked by hand against a running Keycloak.
- **Alternatives:** Client roles (rejected: realm roles are enough for two roles). Opaque tokens with introspection (rejected: a network call per request). Keycloak Testcontainers in CI (rejected: slow, and `jwt()` already covers the rules).

## 15. Accounts API: ownership, visibility and error handling
- **Decision:**
  - The account owner is the JWT `sub`. `POST /accounts` creates only `CUSTOMER` accounts, in `EUR` or `USD` (`Currency` enum). The request has no `type` field, so a `type` sent by a client is ignored. `ownerId` is optional; a USER may only omit it or name themselves (otherwise `403 FORBIDDEN`), an ADMIN may name anyone.
  - `GET /accounts/{id}` returns `404 NOT_FOUND` when the account is missing or not visible to the caller, with the same body in both cases, so ids cannot be probed. USERs only see accounts they own (so never an `EXTERNAL` account); ADMIN sees all. `GET /accounts` applies the same rule and is not paginated yet.
  - `/accounts/**` requires the USER or ADMIN role at the filter chain; ownership is checked in `AccountService`.
  - One `@RestControllerAdvice` (`ApiExceptionHandler`) extends `ResponseEntityExceptionHandler` and returns `ProblemDetail` with a `code`. Spring MVC's own errors (malformed JSON, unknown currency, non-numeric id) become `400 VALIDATION_FAILED`; bean-validation failures add an `errors` list of `{field, message}`. New codes beyond the plan: `FORBIDDEN` (403) and `INTERNAL_ERROR` (500, no detail leaked). Errors produced by the security filter chain itself (401, and 403 for a missing role) are not routed through the advice.
- **Reason:** Hiding the difference between "missing" and "not yours" matters because ids are sequential (see 13). Extending Spring's base handler gives every framework exception a consistent shape without listing each one.
- **Alternatives:** 403 for other people's accounts (rejected: reveals which ids exist). A custom exception per error (deferred until the transfer codes need them). Adding `spring-boot-starter-validation` was approved by the project owner for Bean Validation.

## 16. Deposits and idempotent writes: statuses, amount cap, replay header, hot EXTERNAL row
- **Decision:**
  - **Status mapping:** `IDEMPOTENCY_KEY_REQUIRED` 400; a key longer than 255 characters 400 `VALIDATION_FAILED`; `IDEMPOTENCY_KEY_REUSED`, `INSUFFICIENT_FUNDS`, `CURRENCY_MISMATCH`, `EXTERNAL_ACCOUNT_NOT_ALLOWED` and `SAME_ACCOUNT` 422; a missing or hidden account 404; success 201. The mapping lives in `ErrorCode`, and `LedgerException` carries it to `ApiExceptionHandler`.
  - **Amount cap:** one deposit or transfer is at most 10^12 minor units (`Money.MAX_AMOUNT`), so balance arithmetic cannot approach `long` overflow. Balance changes also use `Math.addExact` and `subtractExact`. An `amount` that is not a whole number (for example `10.5`) is rejected, not truncated (`spring.jackson.deserialization.accept-float-as-int=false`).
  - **Replay header:** a response replayed from a stored idempotency key has the same status and body as the original plus `Idempotency-Replayed: true`. The first response has no such header.
  - **Hot row:** every deposit locks the per-currency `EXTERNAL` account, so deposits in one currency run one at a time. This is accepted: deposits are an admin operation, and the single row is what keeps its balance exact.
  - **Request hash:** SHA-256 over the operation name and the parsed fields (length-prefixed), not the raw body, so JSON formatting differences replay and a different amount, account or currency is `IDEMPOTENCY_KEY_REUSED`. Keys are scoped per caller (`sub`).
  - **Missing header:** `Idempotency-Key` is the first controller parameter, so a missing header is reported before the body is read.
  - **Lock freshness:** accounts are first loaded with `SELECT ... FOR UPDATE`. A plain read before the lock would leave a stale entity in the persistence context, so lookups before the lock use ids only.
  - **Currency mismatch message:** `CURRENCY_MISMATCH` has a fixed message that does not name any currency. The old text revealed the currency of the other account, which a caller may not be allowed to see.
- **Reason:** Stable, distinct statuses let clients branch on status and code. The cap and exact arithmetic remove an overflow class. Rejecting fractional amounts matters because silent truncation of money is a correctness bug, and an integration test caught it.
- **Alternatives:** 409 for rule violations (rejected: 422 says the request was understood but cannot be applied). Hashing the raw body (rejected: whitespace changes would break replays). Seeding several `EXTERNAL` accounts per currency to spread the hot row (rejected: breaks the one-per-currency rule in decision 12).

## 17. Transfers: check order, visibility and fresh balances
- **Decision:**
  - **Request:** `POST /transfers` takes `sourceAccountId`, `targetAccountId`, `amount` and `currency`. The currency must equal both accounts' currency, otherwise `CURRENCY_MISMATCH`.
  - **Check order inside the idempotent work:** (1) a non-admin must own the source, checked with an owner-id-only query, else `404 NOT_FOUND`; (2) `SAME_ACCOUNT`; (3) lock both accounts in ascending id order; (4) `EXTERNAL_ACCOUNT_NOT_ALLOWED`; (5) `CURRENCY_MISMATCH`; (6) debit, which raises `INSUFFICIENT_FUNDS`. Ownership comes before `SAME_ACCOUNT` so that rule cannot probe accounts the caller cannot see. A USER naming an `EXTERNAL` source gets 404 (it has no owner), a USER naming an `EXTERNAL` target gets 422, an ADMIN gets 422 for either.
  - **Fresh balance:** the funds check reads the balance of an entity first loaded by `SELECT ... FOR UPDATE`. Nothing loads an `Account` entity earlier in the transaction: the ownership check is a scalar query (`AccountLocking.ownerIdOf`). A plain `findById` before the lock would leave a stale entity in the persistence context, and the check would pass on an old balance.
  - **Reads:** `GET /transfers/{id}` is visible to the owner of the source or target account, and to an ADMIN. Everyone else gets the same 404 as for a missing id, so transfer ids cannot be probed. A deposit is therefore readable by its recipient.
  - **Responses:** 201 with a `Location: /transfers/{id}` header, also on a replay, plus `Idempotency-Replayed: true` on a replay.
- **Reason:** Ids are sequential (decision 13), so every "not yours" case must look like "not found". Reading the balance after the lock is what makes the funds check and the `balance >= 0` constraint agree under concurrency.
- **Tests:** concurrent transfers with different keys from one account that can afford only some of them (successes equal balance / amount, the balance never goes negative), opposite-direction transfers (no deadlock), and an invariants test (signed entries sum to zero per transfer, every cached balance equals its entry sum).
- **Alternatives:** 403 for a foreign source (rejected: reveals which ids exist). Refreshing the entity after locking (works, but the owner-id-only query avoids the stale read by construction).

## 18. Balance and history reads
- **Decision:**
  - `GET /accounts/{id}/balance` returns the cached `accounts.balance` (decision 5). Tests cross-check it against the sum of the entries, and the history pages against both.
  - `GET /accounts/{id}/transactions` lists the account's own ledger entries (not transfers), paged with `page` and `size`. The default size is 20 and the cap is 100: a larger `size` is lowered to 100, a `size` below 1 or a negative `page` is `400 VALIDATION_FAILED`. A page past the end is `200` with empty content and the real totals; if `page * size` would overflow Hibernate's int offset, the query is skipped and only the count runs.
  - `sort` is `createdAt` or `amount` with `asc` or `desc` (default `createdAt,desc`). The entry id is always the last sort key, in the same direction, so equal keys page without gaps or repeats. Only listed fields are accepted, so a client cannot sort by arbitrary columns.
  - `from` is inclusive and `to` is exclusive, as strict ISO-8601 instants. Half-open ranges let consecutive windows tile without overlap. `from` after `to` is 400; `from` equal to `to` is an empty range. Spring's default `Instant` binding is not used because it also accepts bare epoch numbers, which are ambiguous between seconds and milliseconds.
  - Visibility is the rule of `GET /accounts/{id}`: a USER sees accounts they own, an ADMIN sees all, and a missing and a hidden account give the same 404. It lives in `AccountAccess.requireVisible` for the `ledger` package. `AccountService.get` still applies the same rule for single-account reads; both are covered by the same visibility tests.
  - V7 adds `ledger_entries (account_id, amount, id)` for amount sorting. Date sorting is already served by the V3 index `(account_id, created_at DESC, id DESC)`, which Postgres can scan in either direction.
- **Reason:** The balance read is a single-row lookup and needs no aggregate. The history query is offset-paged, which is simple and enough for a portfolio ledger.
- **Costs accepted:** Offset paging gets slower on very deep pages, and rows inserted between two page requests can shift the pages. Keyset paging on `(createdAt, id)` would fix both and is the next step if histories grow large. The balance is a cached value; if it ever drifts from the entries, reconciliation (milestone 10) is what detects it, not this endpoint.
- **Alternatives:** Spring's `Pageable` argument resolver (rejected: it accepts any property in `sort` and has a much higher size cap by default). Computing the balance from entries on read (rejected: an aggregate per request, and it would hide drift instead of exposing it).
