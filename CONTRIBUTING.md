# Contributing to popdesk

Thank you for your interest. popdesk is young and not ready for production. Questions, bug reports, and comments on the plan are as welcome as code.

## Before you start

- Read [`design/roadmap.md`](design/roadmap.md) (scope and order) and [`design/adr.md`](design/adr.md) (decisions). These are in Korean; feel free to ask in English.
- For anything bigger than a small fix, open an issue first so we can agree on the approach.
- Follow the [Code of Conduct](CODE_OF_CONDUCT.md) and the [AI policy](AI_POLICY.md).
- Report security problems through [SECURITY.md](SECURITY.md), never in public.

## How changes are made

We work in feature slices. Each slice goes through the same steps: acceptance criteria, contract, tests, implementation, verification. The full process is in [`design/process.md`](design/process.md). In short:

| Kind of change | What we expect |
|---|---|
| Small (fits in one sentence) | Tests, code, and green CI |
| One feature | Acceptance criteria, contract, tests, then code |
| Touches a database migration, the customer-company boundary, personal data, a public contract (settings, compose, documented API), or a shared UI component API | The same as one feature, plus a design review before code. Mention it in the pull request |

Rules that apply to every change:

- Tests come first and define done. Do not weaken or delete a test to make your change pass. If a test looks wrong, say so in the pull request.
- Database migrations only move forward. Never edit a migration that has been merged.
- Every query that returns customer data must respect the customer-company boundary, and the change must include a test showing that one company cannot see another's data.
- Keep `main` green.

## Sign your commits (DCO)

We use the [Developer Certificate of Origin](https://developercertificate.org/). Add a sign-off line to every commit:

```
git commit -s -m "Describe the change"
```

This adds `Signed-off-by: Your Name <you@example.com>` and states that you have the right to submit the work under the Apache License 2.0.

## Development setup

You need JDK 25, Node 24.12 or newer, pnpm (the version in `web/package.json`), Python 3, and Docker. Many server tests start PostgreSQL in a container.

- Server, in `server/`: `./mvnw -B test -Dtest=ClassA,ClassB` runs the named test classes. The whole suite takes hours on a laptop; run the classes your change touches and let CI run the rest.
- Web, in `web/`: `pnpm install`, then `pnpm --filter @popdesk/console test` (or `portal`, `ui`), `pnpm typecheck`, `pnpm lint`, and `pnpm dup`.
- Repository checks, from the root: `python scripts/ci/tests-lock.py check` and `python scripts/ci/check-test-style.py`.
- The container image: `docker build -t popdesk:dev .` from the root.

Tests are locked (`scripts/ci/tests.lock`): a pull request that changes a test says why, and the maintainer relocks it. Structure is kept by checks (no new cycle between server packages, no import between the two web apps, a limit on duplicated web code); when one fails, move the code instead of editing the baseline.

## Documentation

- `docs/` is public and written in English.
- `design/` is the builders' record and written in Korean.

If your change alters behavior that users or operators see, update the matching page in `docs/` in the same pull request.
