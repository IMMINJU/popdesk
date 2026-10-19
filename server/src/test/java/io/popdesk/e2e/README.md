# Empty-install E2E

These tests run a popdesk installation from the release Compose file, the way an operator does,
and drive the first slice's flow over HTTP (`design/archive/0-first/acceptance.md` section 1,
S0-AC-94). They use no browser; the web tests cover the screens. Both classes are tagged `deploy`,
so the server job leaves them out and the image job runs them.

They were written as the hidden E2E (`design/process.md` section C, "막는 장치") and moved here after
they passed on the merged first slice. From now on they are ordinary regression tests.

## What runs

| Test | What it does |
|---|---|
| `MissingAdminInstallTest` | Starts on empty volumes without `POPDESK_ADMIN_EMAIL`/`POPDESK_ADMIN_PASSWORD`. The app container must turn `unhealthy`, stay so for 30 seconds without restarting, and name both keys in its log |
| `EmptyInstallFlowTest` | Steps in order (`@Order`). Starts on empty volumes with only the first admin values and the database password (`POPDESK_DB_PASSWORD`; secrets have no default): healthy, admin signs in on the console host. Without SMTP, an agent joins with a copied invitation link. Then the same installation is reconfigured behind Caddy (TLS), with Mailpit as SMTP and a webhook receiver, and the flow runs through the proxy: company, invitations by email and acceptance, an issue with a 10 MB attachment, webhook, agent answer, customer email, customer reply, metrics, and a check that the logs hold no secrets |

A step that fails makes every later step fail with an assertion that names it. Nothing is skipped.

## Running them

1. Build the image from the repository root: `docker build -t popdesk:test .`
2. From `server/`: `./mvnw test -Dtest='io.popdesk.e2e.**' -Dtest.excludedGroups=openapi`

| Variable | Meaning | Default |
|---|---|---|
| `POPDESK_TEST_IMAGE` | Image under test. The tests never build it | `popdesk:test` |
| `POPDESK_REPO` | popdesk checkout; the tests use its `deploy/compose.yml` as it is | the parent of `server/` |

The tests replace only the `app` image, with an override file. Settings go in a `.env` file in
the Compose project directory (`target/e2e/<project>/`), which Compose reads both for `${...}`
substitution and for `env_file: .env`.

Before an installation is started, the tests check that Docker is reachable, the image exists,
the Compose file exists, and the volumes `popdesk-db` and `popdesk-attachments` do not exist yet.
The Compose file fixes these volume names, so **only one such installation can exist on the
machine at a time**: do not run these classes in parallel with each other or next to a real
installation. The deploy tests rename their volumes and do not collide. Each test removes its
installation at the end with `docker compose down -v`.

## Docker resources

One installation at a time: `app`, `db` (PostgreSQL 16), and for the flow three small stand-ins on
the Compose network (`axllent/mailpit`, `caddy`, `python`). Plan for about 2 GB of memory. The app
publishes port 8080 on the host, as the release Compose file says; it must be free. The proxy uses
a random host port, which is also the installation's `POPDESK_PUBLIC_PORT`.

The test JVM resolves `*.e2e.test` and `*.localhost` to `127.0.0.1` itself
(`TestHostsResolverProvider`, registered in `META-INF/services`), so links from emails and
responses are opened as they are. No hosts file change is needed.

Logs of each run are saved under `server/target/e2e/<project>/`.
