# popdesk web

pnpm workspace for the two browser apps and their shared packages (ADR D-7).

## Layout

```
web/
├─ portal/            customer portal (Vite, React, TypeScript)
├─ console/           agent console (Vite, React, TypeScript)
├─ packages/
│  ├─ ui/             design system parts
│  ├─ api/            generated API types and clients
│  └─ i18n/           messages (ko, en)
├─ e2e/               Playwright config (tests come per slice)
├─ eslint.config.js   one flat config for every project
└─ tsconfig.base.json strict settings every tsconfig extends
```

Tool versions are pinned once, in the `catalog` of `pnpm-workspace.yaml`.
Packages refer to them with `"catalog:"`.

## Requirements

- Node 24 (`.nvmrc`, `engines`)
- pnpm 12.10.1 (`packageManager`). With corepack: `corepack enable pnpm`.

## Commands

Run from `web/`.

| Command | What it does |
|---|---|
| `pnpm install` | Install dependencies |
| `pnpm gen:api` | Regenerate `packages/api/src/generated/*.ts` from `../docs/integrate/openapi-portal.json` and `openapi-console.json` |
| `pnpm typecheck` | `tsc` in every project |
| `pnpm lint` | ESLint in every project |
| `pnpm test` | Vitest in every project, one project at a time |
| `pnpm build` | Build both apps into `portal/dist` and `console/dist` |
| `pnpm changed:test` | Test only projects changed since `origin/main` and the projects that depend on them (ADR D-48). Also `changed:build`, `changed:typecheck`, `changed:lint` |
| `pnpm --filter @popdesk/portal dev` | Portal dev server on `http://portal.localhost:5173` |
| `pnpm --filter @popdesk/console dev` | Console dev server on `http://console.localhost:5174` |

`pnpm gen:api` reads the OpenAPI files from another folder when
`POPDESK_OPENAPI_DIR` is set. Commit the generated files.

The `changed:*` commands only see changes inside a project. A change to a
root file (`eslint.config.js`, `tsconfig.base.json`, `pnpm-workspace.yaml`,
`pnpm-lock.yaml`) needs the full commands.

## API clients

The server splits its API by app and tells the apps apart by host (ADR D-43).
`@popdesk/api` has no root entry point, only one per app:

```ts
import { createPortalClient } from '@popdesk/api/portal';   // portal only
import { createConsoleClient } from '@popdesk/api/console'; // console only
```

ESLint rejects the other app's entry point, so console types never reach the
portal bundle and the reverse. Clients send cookies same-origin.

In development each app's dev server forwards `/api` to `http://localhost:8080`
with the Host header `portal.localhost` or `console.localhost`.

## E2E

`e2e/playwright.config.ts` targets the running app: the portal at
`http://portal.localhost:8080` and the console at `http://console.localhost:8080`.
Install browsers with `pnpm --filter @popdesk/e2e exec playwright install chromium`,
then run `pnpm --filter @popdesk/e2e e2e`.
