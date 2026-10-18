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

| Table | Owned by | What it holds |
|---|---|---|
| `tenant` | `helpdesk` | The team. One row, `id` 1, made on the first start |
| `account` | `helpdesk` | Accounts: email, name, role, status, Argon2id hash, language, sign-in lockout, and when the password last changed (V5): a session of a sign-in from before that time lets nobody in. `anonymized_at` (V22) is set for an anonymized account only; such an account keeps the anonymous name and `deleted-{id}@invalid` and is never brought back |
| `invitation` | `helpdesk` | The invitation of an account: SHA-256 of the token, when it was sent and expires, and when it was cancelled (V5). The row stays after acceptance; a token works only while the account is `INVITED` |
| `password_reset` | `helpdesk` | The password reset link of an account (V5): SHA-256 of the token, when it was made, expires, and was used. One row per account; a new link replaces it. The cleanup `password-reset-cleanup` deletes used and expired rows |
| `account_photo` | `helpdesk` | The profile photo of an account (V5): a PNG of at most 256 px a side, its size, and when it changed |
| `audit_log` | `helpdesk` | Audit rows: action, person (`{accountId, name, email}`), target, changes, time |
| `company` | `helpdesk` | Customer companies, and the number of each company's last issue |
| `company_member` | `helpdesk` | Which customers belong to which company |
| `company_agent` | `helpdesk` | Which agents are assigned to which company |
| `issue` | `helpdesk` | Issues; the key is the company code and `number` |
| `comment` | `helpdesk` | Comments, public and internal. `edited_at` when the author changed the content; a deleted comment keeps its row with an empty `content`, `deleted_at`, and `deleted_by` (V12) |
| `comment_mention` | `helpdesk` | The accounts a comment mentions (V12). The rows stay when the comment is deleted |
| `attachment` | `helpdesk` | Attachment metadata and state, and where the file is stored (`storage`, `storage_location`). `pending` (row committed, file being written; no issue yet for a file sent with a new issue), `stored`, `purging` (delete committed, file not removed yet), `purged`. Only `stored` needs an issue (V4): the cleanup ends a failed filing's files without one. Rows stay after a delete as traces; a row the cleanup ended has no `deleted_by` and is not listed |
| `attachment_content` | `helpdesk` | File contents when `POPDESK_STORAGE=db` |
| `spring_session`, `spring_session_attributes` | `helpdesk` | Sign-in sessions (Spring Session JDBC) |
| `branding` | `helpdesk` | The branding of the installation (V21): one row, `id` 1, with the display name, the accent color (`#RRGGBB` in capitals, null for the app's own colors), and a version that goes up when either changes. V21 puts in the default row: `popdesk`, no accent color, version 1 |
| `branding_logo` | `helpdesk` | The logo of each theme (V21), at most one per `theme` (`LIGHT`, `DARK`): the PNG popdesk made from the upload, never the uploaded file, its SHA-256, and its size within 512 by 128 px. A logo change does not change the branding's version |
| `outbox` | `notification` | Emails and webhooks waiting to be sent, and the ones that failed. `kind` is a `MailKind` name for `MAIL` (`INVITATION`, `PASSWORD_RESET`, `STAFF_COMMENT`, `ISSUE_RESOLVED`, `MENTIONED`, ...) and a `WebhookKind` name for `WEBHOOK`. A row holds IDs only; `company_code` is set for a `MEMBERSHIP_REQUESTED` email alone (V12) |
| `delivery_status` | `notification` | Last successful send per channel; both rows are made at startup |
| `ops_alert` | `notification` | Operations alerts that are raised, one row per kind (`OpsAlerts.Kind`) until resolved |
| `notification` | `notification` | Inbox items of every account. `kind` is an `OpsAlerts.Kind` name or `MEMBERSHIP_REQUESTED` for the items of admins about no issue, and an `IssueInbox.Kind` name for an item about an issue, which has `issue_id`, `actor_id`, and for a comment `comment_id` (V12). The title and the names are read when the inbox is read |
| `cleanup_run` | `notification` | One row per cleanup job: when it last ran and how many rows it deleted. `helpdesk`'s jobs are `CleanupJob` beans that `notification` runs and records |

`notification` reads accounts, issues, and comments only through interfaces it declares and `helpdesk` implements (`Recipients`, `IssueFacts`, `IssueAccess`, `InboxFacts`, `CommentFacts`, `InvitationLinks`, `PasswordResetLinks`, `BrandingName`), so it does not depend on `helpdesk`.

Who is told of what happens on an issue is decided in one class, `helpdesk.issue.IssueNotifications`: it leaves out the person who did it, accounts that are not `ACTIVE`, and accounts that cannot see the issue, and calls `IssueInbox.add` once per person, which writes the inbox item and queues the email together. Services do not queue issue emails themselves.

Migrations live in `src/main/resources/db/migration/` and only move forward (ADR D-24). A file is `V<n>__<what_it_does>.sql`, with `n` the next whole number. Versions 999 and up belong to test fixtures; the app's own stay below.

### Tables without `tenant_id`

Every business table has `tenant_id bigint NOT NULL` with the value 1 (ADR D-18, S0-AC-05). These tables are the only exceptions. Each is named on its own; no pattern makes an exception. The same list is in `scripts/ci/check-tenant-id.py`, and a test checks that both agree.

| Table | Reason |
|---|---|
| `flyway_schema_history` | Flyway makes it and decides its shape. It is the schema history of one installation, not team data |
| `spring_session` | Spring Session JDBC decides its shape. A session points at an account, and the account has `tenant_id` |
| `spring_session_attributes` | The same as `spring_session` |
| `tenant` | The team table itself: it is what `tenant_id` points to |
