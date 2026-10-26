# Payments Ledger Service

Portfolio project: a double-entry payments ledger with idempotent transfers.

## Stack
Java 21, Spring Boot 4.1.x (4.x starters: separate Flyway and OAuth2 resource-server starters), Spring Security (OAuth2 resource server, JWT from Keycloak),
Spring Data JPA, PostgreSQL, Flyway, Maven (use the wrapper `./mvnw`),
Testcontainers, Docker Compose, GitHub Actions.

## Ports
- App: 8080
- Keycloak: 8081
- PostgreSQL: 5432
- Prometheus: 9090
- Grafana: 3000

## Commands
- Build and test: `./mvnw clean verify` (Git Bash) or `.\mvnw clean verify` (PowerShell)
- Run tests only: `./mvnw test` (Git Bash) or `.\mvnw test` (PowerShell)
- Start dependencies: `docker compose up -d`
- Stop dependencies: `docker compose down`

(Docker Desktop must be running for tests, because Testcontainers needs it.)

## Conventions
- Package by feature (account, transfer, ledger), not by layer.
- No business logic in controllers; controllers validate input and delegate to services.
- All schema changes go through new Flyway migrations. Never edit an old migration.
- Money is stored as integer minor units plus a currency code. Never use double or float.
- Use DTOs for the API. Never expose JPA entities.
- Ledger entries are append-only. Never update or delete them; corrections are new entries.
- Consistent error responses via a single @ControllerAdvice.
- Every new feature needs unit tests and a Testcontainers integration test.

## Working rules
- Work in small steps. Run the tests before saying something is done.
- Ask before adding a new dependency.
- Keep the README and OpenAPI docs up to date when the API changes.
- Record important design decisions in docs/DECISIONS.md.
- Track milestones in docs/PLAN.md and tick them off when finished.
- Never commit secrets. Use environment variables or local profile files that are git-ignored.
