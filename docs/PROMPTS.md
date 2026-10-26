# Prompt sequence for the Payments Ledger Service

Send these to Claude Code ONE AT A TIME. After each prompt: read the diff, run the tests,
commit with a clear message, then send the next one. Use /clear between phases.

Model suggestion: Sonnet 5.5 by default. Use Opus 5.5 for prompts 1, 9 and 15.
Use Haiku 5.5 for small edits.

Before starting: CLAUDE.md, .gitignore and README.md are in the repo root, and this file
is in docs/. Docker Desktop is running.

---

## Phase 1: Plan and foundation

### Prompt 1: Planning (use plan mode, Opus)
```
Build a payments ledger service as a portfolio project. Stack: Java 21,
Spring Boot 3, PostgreSQL, Flyway, Keycloak (JWT), Testcontainers,
Docker Compose, GitHub Actions, Maven.
Before writing code, propose: architecture, domain model, API endpoints,
package structure, and a milestone plan of small steps. Ask me about
anything ambiguous. Do not write code yet.
```

### Prompt 2: Save the plan
```
Save the agreed milestone plan to docs/PLAN.md as a checklist. Also save the
key architecture decisions to docs/DECISIONS.md, one short entry per decision
with the reason and the alternatives considered.
```

### Prompt 3: Scaffold
```
Implement milestone 1 only: Maven project skeleton, package-by-feature
structure, Docker Compose with Postgres and Keycloak, application profiles
(local, test), a health endpoint, and a basic passing test. Update the
commands section of CLAUDE.md. Run the build and show me it passes.
```

### Prompt 4: Database schema
```
Create Flyway migrations for accounts, ledger_entries, transfers, and
idempotency_keys. Money is stored as integer minor units with a currency
code. Add constraints (non-null, foreign keys, unique idempotency key,
check that entries have a positive amount) and indexes for the main
queries. Add a Testcontainers test that boots Postgres and verifies the
migrations apply cleanly.
```

### Prompt 5: CI
```
Add a GitHub Actions workflow that builds the project and runs all tests
(including Testcontainers) on every push and pull request. Add Maven
dependency caching.
```

---

## Phase 2: Security

### Prompt 6: Authentication
```
Secure the API with Spring Security as an OAuth2 resource server validating
JWTs from Keycloak. Add a Keycloak realm export in Docker Compose with two
roles: USER and ADMIN. Public endpoints: health only. Write tests for
401 (no token), 403 (wrong role), and 200 (valid token) using Spring
Security test support.
```

---

## Phase 3: Feature slices

### Prompt 7: Accounts
```
Implement account creation and lookup: POST /accounts, GET /accounts/{id}.
Use DTOs, Bean Validation, and a consistent error response format via
@ControllerAdvice. Users can only see their own accounts; ADMIN can see
all. Write unit tests and integration tests.
```

### Prompt 8: Transfers (the core feature)
```
Implement POST /transfers between two accounts using double-entry
bookkeeping: one debit entry and one credit entry that sum to zero, written
in a single transaction. Require an Idempotency-Key header: a repeated
request with the same key returns the original result without creating a
second transfer. Reject insufficient funds, same-account transfers, and
currency mismatches with clear error codes. Write unit tests and
Testcontainers integration tests including duplicate-request cases.
Run the tests and fix failures.
```

### Prompt 9: Concurrency (Opus)
```
Make transfers safe under concurrency. Choose and implement a locking
strategy (pessimistic locking with consistent lock ordering to avoid
deadlocks, or optimistic locking with retry) and record the reasoning in
docs/DECISIONS.md. Write a test that fires 50 concurrent transfers against
the same account and asserts the final balance is correct and no money is
created or lost.
```

### Prompt 10: Balance and history
```
Implement GET /accounts/{id}/balance (computed from ledger entries) and
GET /accounts/{id}/transactions with pagination, sorting, and date-range
filtering. Add the necessary indexes via a new Flyway migration. Write
tests including an account with no entries and large page numbers.
```

### Prompt 11: Reversals
```
Implement POST /transfers/{id}/reverse (ADMIN only). A reversal creates
new compensating ledger entries and never edits or deletes existing ones.
A transfer can be reversed only once. Write tests for the normal case,
double reversal, and reversing a non-existent transfer.
```

### Prompt 12: Reconciliation
```
Add a scheduled job and an ADMIN endpoint that verifies ledger integrity:
total debits equal total credits, and each account balance matches the sum
of its entries. Return a report of any mismatches. Write a test that
deliberately corrupts data and checks the job detects it.
```

---

## Phase 4: Production quality

### Prompt 13: Observability
```
Add Spring Boot Actuator, Micrometer metrics (transfer count, transfer
failures, transfer latency), structured JSON logging with a correlation ID
per request, and a Prometheus plus Grafana service in Docker Compose with
a basic dashboard. Never log sensitive data.
```

### Prompt 14: API documentation
```
Add springdoc-openapi so Swagger UI is available, with request and response
examples, error responses, and the security scheme documented. Add a
Postman collection or .http file with example calls for every endpoint.
```

### Prompt 15: Hardening (Opus)
```
Review the whole codebase as a senior reviewer. Check for security issues,
missing validation, transaction boundary problems, N+1 queries, weak
tests, and inconsistent error handling. List findings by severity, then
fix the high and medium ones one commit at a time.
```

### Prompt 16: Test coverage
```
Add JaCoCo and report test coverage. Identify the most important untested
paths (not just uncovered lines) and add tests for them. Add a coverage
summary to the CI output.
```

### Prompt 17: Containerize
```
Add a multi-stage Dockerfile for the application (non-root user, small
runtime image) and extend Docker Compose so that one command starts the
app, Postgres, Keycloak, Prometheus, and Grafana. Verify that it works
from a clean clone.
```

---

## Phase 5: Presentation

### Prompt 18: README
```
Write a README with: what the project is, a Mermaid architecture diagram,
quick start (one command), API examples with curl, design decisions and
trade-offs (idempotency, locking, double-entry), how to run the tests, and
a "what I would do next" section. Keep it honest and concise.
```

### Prompt 19: Final check
```
Clone-and-run audit: follow the README exactly as a new developer would,
note every step that fails or is unclear, and fix them. Also check for
committed secrets, unused dependencies, and leftover TODOs.
```

---

## Reminders
- Prompts 8, 9, 11 and 12 are where you learn the most. Make sure you can explain them.
- If Claude Code asks you a question, answer it. Your choices become your interview talking points.
- Commit after every step so the Git history shows clear progress.
