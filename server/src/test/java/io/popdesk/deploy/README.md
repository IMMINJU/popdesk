# Deploy tests

Tests of the release artifacts: the image, the Compose files, the `popdesk` commands, the Grafana dashboards, and the upgrade test harness. They run the real image with Docker Compose, not the app in the test JVM. All classes are tagged `deploy`.

## Running them

1. Build the image from the repository root. The tests never build it.

   ```
   docker build -t popdesk:test .
   ```

   To test another image, set `POPDESK_TEST_IMAGE` (for example `POPDESK_TEST_IMAGE=ghcr.io/imminju/popdesk:0.1.0`).

2. Run the tests from `server/`:

   ```
   ./mvnw test -Dtest='io.popdesk.deploy.**'
   ```

Needs Docker with Compose v2.24 or later (the override file uses `!override` and `!reset`), and Python 3 on the `PATH` as `python` for the upgrade harness (or set `POPDESK_PYTHON`). Each test class starts one Compose project at a time and removes it with `down -v` at the end. The observability test pulls `grafana/otel-lgtm` and needs about 1.5 GB of memory while it runs.

## What the tests assume

| Thing | Assumption |
|---|---|
| Dockerfile | `Dockerfile` at the repository root |
| Image under test | `POPDESK_TEST_IMAGE`, else `popdesk:test` |
| Release Compose file | `deploy/compose.yml`, services `app` and `db` |
| Development Compose file | `deploy/compose.dev.yml`, laid over the release file |
| Observability Compose file | `deploy/compose.obs.yml`, dashboards in `deploy/grafana/*.json` |
| Commands | `popdesk` on the `PATH` inside the app container. Restore runs in a new container, `docker compose run --rm app popdesk restore <file>`; dumps move with `docker compose cp` |
| JVM options | The image sets the garbage collector in its own JVM options (`-XX:+Use...GC` in its environment, entry point, or the `java` command line). It launches the app with the `java` launcher and leaves `JDK_JAVA_OPTIONS` to the operator. The tests set the heap (`-Xmx384m`) and the trust store for the mail server this way, and change nothing else about the JVM |
| Database settings | The release file reads `POPDESK_DB_PASSWORD` from the environment (`${POPDESK_DB_PASSWORD}`), as an operator's `.env` gives it |
| Migrations | In Flyway's default location `classpath:db/migration`, with versions below 999 |

`Stack` starts a project from these files plus a generated override that only makes it safe to run next to other tests: the image under test, random host ports (and `app` 8081 and `db` 5432 published for the test), volume names with the project name in front, and the first admin. Everything else comes from the repository's files.

## The upgrade harness and the sample generator

The slice leaves their interface open; the tests fix it as follows. Both live in `deploy/upgrade/`, run with Python 3, start their own Compose projects from `deploy/compose.yml` (renaming the volumes, as `Stack` does), pass their environment on to `docker compose`, and remove their projects when they end.

```
python deploy/upgrade/make-sample.py --image IMAGE --out DIR --project NAME
```

Starts IMAGE on an empty database, fills every business table of the slice through the API (and SQL only where the API cannot reach a state), including the boundary values: an attachment whose file name has 255 characters, an attachment in state `purging`, and a send queue row in state `failed`. Then runs `popdesk backup` and writes:

| File | Content |
|---|---|
| `DIR/sample.dump` | the dump from `popdesk backup` |
| `DIR/sample.image` | the image ID (`sha256:...`) of the image that made it |
| `DIR/sample.expected.json` | what the sample check expects: issue count and keys, attachment content hashes, queue rows, audit rows |

Exit code 0 when all of this worked.

```
python deploy/upgrade/upgrade-test.py --sample DIR --image IMAGE --work WORK --project NAME [--compose-file FILE ...]
```

Restores `DIR/sample.dump` into a new, empty database with `popdesk restore`, starts IMAGE on it, waits until it is healthy (migrations done), and runs the sample check against `DIR/sample.expected.json`. Each `--compose-file` is added after `deploy/compose.yml` (the self test uses one to add a Flyway location). Writes:

| File | Content |
|---|---|
| `WORK/result.json` | `{"started": bool, "checks": {"issues": bool, "attachments": bool, "outbox": bool, "audit": bool}}` |
| `WORK/app.log` | the app's log |

| Exit code | Meaning |
|---|---|
| 0 | the app started and every check passed |
| 1 | the app started and a check failed |
| 2 | the app did not start or did not finish migrating |
| other | the harness itself failed |

## Test resources

`server/src/test/resources/deploy/` (see `README.txt` there): the mail server's key and trust store, a Compose file with a GreenMail container on port 465, and the broken `V999` migration for the harness self test.
