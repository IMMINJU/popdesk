# popdesk server

One Spring Boot app (Java 25, Maven) that serves the customer portal API (`/api/portal/**`), the agent console API (`/api/console/**`), and the public API used by both before sign-in (`/api/public/**`). Design: `design/spec/architecture.md`.

## Requirements

- JDK 25 (`JAVA_HOME` pointing at it)
- Docker, for the tests: they start PostgreSQL 16 with Testcontainers

Maven itself comes from the wrapper (`./mvnw`, or `mvnw.cmd` on Windows).

## Commands

Run them from `server/`.

| What | Command |
|---|---|
| Build and run all tests | `./mvnw verify` |
| Tests only | `./mvnw test` |
| Tests of one module | `./mvnw test -Dtest='io.popdesk.helpdesk.**'` (add `-Dsurefire.failIfNoSpecifiedTests=false` while a module has no tests) |
| Module boundaries | `./mvnw test -Dtest=ModulesTest` |
| Write the OpenAPI files | `./mvnw -Popenapi test` |
| Run the app on a throwaway database | `./mvnw spring-boot:test-run` |

Tests run one class after another in a single JVM with a small heap, so they fit on a 16 GB machine next to the web apps.

### Tests of a changed module

The modules are packages in one Maven project, not Maven modules. A module's tests live in its package, so `-Dtest='io.popdesk.<module>.**'` runs them. `helpdesk` and `notification` call each other's public API (architecture section 3), so a change to either one runs both. `ModulesTest` runs whenever any module changes.

### OpenAPI files

`./mvnw -Popenapi test` starts the app on a Testcontainers database, reads the two springdoc groups, and writes:

- `docs/integrate/openapi-portal.json`: `/api/portal/**` and `/api/public/**`
- `docs/integrate/openapi-console.json`: `/api/console/**` and `/api/public/**`

The output is the same on every run: keys sorted, two-space indent, LF line ends, a line break at the end, and no `servers`. Commit both files with the code that changes an endpoint; CI regenerates them and fails on any difference (ADR D-21, D-43). The running app does not serve `/v3/api-docs`.

## Configuration

The database connection comes from `POPDESK_DB_URL`, `POPDESK_DB_USER`, and `POPDESK_DB_PASSWORD`. All variables are listed in `docs/operate/configuration.md`.

## Modules

Spring Modulith modules (ADR D-6). Each table has one owning module, and a module does not read another module's tables.

| Module | Package | Owns |
|---|---|---|
| `helpdesk` | `io.popdesk.helpdesk` | Accounts, invitations, sign-in, customer companies, issues, comments, attachments, labels, audit log, statistics |
| `notification` | `io.popdesk.notification` | Outbox, outgoing email, webhooks, incoming email |

### Tables

| Table | Module |
|---|---|
| (none yet) | |

Migrations live in `src/main/resources/db/migration/` and only move forward (ADR D-24).
