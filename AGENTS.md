# AGENTS.md

Instructions for AI coding agents working on popdesk. Keep this file short; details live in the linked files.

## Read first

- `design/roadmap.md`: scope, out of scope, slice order
- `design/process.md`: how a slice is built, the three paths, where artifacts go
- `design/adr.md`: decisions. Read the rows the slice documents cite (by ID), not the whole file; do not contradict one without a new ADR
- `design/slices/`: the slice in progress (acceptance criteria, tasks)

Order of authority when documents disagree: roadmap → ADR → canonical docs (OpenAPI, conventions, permission model, threat model) → slice in progress → `design/spec/` drafts. If they disagree, stop and report; do not guess.

## Rules

- Tests come first and are locked. Never edit or delete a test to make your code pass. If a test looks wrong, stop and report.
- The agent that writes a slice's tests does not implement it, and the reverse.
- A test does not depend on test order or on state another test class set. JVM-wide options for tests go in the Surefire `argLine`.
- Migrations only move forward. Never edit a merged migration.
- Every business table has `tenant_id bigint NOT NULL`. Do not filter by it yet (ADR D-18).
- Any query that returns customer data respects the customer-company boundary, with a negative test (company A cannot see company B).
- Never put secrets, tokens, invitation links, or message content in logs. Email addresses only masked after the first character (PII-2).
- The API is split by app: `/api/portal/**` for the customer portal, `/api/console/**` for the agent console, `/api/public/**` for both (ADR D-43). springdoc generates one contract per app into `docs/integrate/openapi-portal.json` and `docs/integrate/openapi-console.json`; regenerate them and the TypeScript clients when endpoints change.
- If a change touches a migration, the customer-company boundary, personal data, a public contract, or a shared component API, it is on the full path (`design/process.md`); say so.

## Running things

- Run only the tests for what you changed, one runner at a time (the dev machine has 16 GB). CI also runs only the jobs for the parts a change touches; release tags run everything (ADR D-48). When you add a CI job, give it a path filter.
- After a push, watch the CI run to the end (`gh run watch`). After a scripted edit, check the result with `grep` before reporting it as changed.
- Commands will be listed here once the first slice lands.

## Writing

- `docs/` and root files: English.
- `design/`, commit bodies, and notes for the maintainer: Korean, plain words, no filler.
- Commits use DCO sign-off (`git commit -s`).
