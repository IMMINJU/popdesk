## What and why

<!-- One or two sentences. Link the issue or slice (design/slices/...). -->

## Size of change

See [design/process.md](../design/process.md), section C.

- [ ] Light: fits in one sentence (tests, code, green CI)
- [ ] Standard: one feature (acceptance criteria, contract, tests, then code)
- [ ] Full: touches any item below

Does this change touch any of these? If yes, it is on the full path whatever its size, and needs a design review before code.

- [ ] A database schema migration
- [ ] The customer-company boundary
- [ ] The flow of personal data
- [ ] A public contract (settings, compose, documented API)
- [ ] A breaking change to a shared component API

## Checks

- [ ] Tests came first; no existing test was edited or deleted to make this pass (if one looks wrong, explain below)
- [ ] Every query that returns customer data has a negative test: company A cannot see company B's data
- [ ] No merged migration was edited (migrations only move forward)
- [ ] OpenAPI files and TypeScript clients are regenerated, if endpoints changed
- [ ] No secrets, tokens, invitation links, or email addresses in logs
- [ ] `docs/` is updated, if users or operators see a change

## Sign-off

Every commit needs a DCO sign-off line (`git commit -s`). See [CONTRIBUTING.md](../CONTRIBUTING.md#sign-your-commits-dco).
