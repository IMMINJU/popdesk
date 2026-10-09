# Upgrade test harness

Checks that a new image upgrades a database made by an older one (ADR D-24, process.md B and D). Python 3, standard library only; needs Docker with Compose v2.24 or later. Both scripts start their own Compose project from `deploy/compose.yml` with the volumes renamed after the project, pass their environment on to `docker compose`, and remove the project when they end.

## Make a sample

```
python deploy/upgrade/make-sample.py --image IMAGE --out DIR --project NAME
```

Starts IMAGE on an empty database and fills every business table through the API, with SQL only for two states the API cannot reach on demand (an attachment in `purging`, a send queue row in `failed`). It includes a 255-character file name. Then it runs `popdesk backup` and writes:

| File | Content |
|---|---|
| `DIR/sample.dump` | the dump |
| `DIR/sample.image` | the image ID (`sha256:...`) that made it |
| `DIR/sample.expected.json` | what the sample check expects: issue keys, attachment hashes, failed queue rows, audit rows |

Exit code 0 when it worked, 3 when it did not. It also fails when a business table is left empty, so a new table without sample data is noticed.

## Run the harness

```
python deploy/upgrade/upgrade-test.py --sample DIR --image IMAGE --work WORK --project NAME [--compose-file FILE ...]
```

Restores `DIR/sample.dump` with `popdesk restore` into a new database, starts IMAGE on it, waits until it is healthy, and checks the sample through the API (issues, attachment contents, as the sample's admin) and SQL (failed queue rows, audit rows). Writes `WORK/result.json` and `WORK/app.log`.

| Exit code | Meaning |
|---|---|
| 0 | the app started and every check passed |
| 1 | the app started and a check failed |
| 2 | the app did not start or did not finish migrating |
| 3 | the harness itself failed |

A failed queue row is deleted by the app 7 days after it failed, so the outbox check lets such an old row be gone. With a sample older than a week the outbox check therefore proves little; see the release steps below.

## At a release

1. Run `make-sample.py` with the released image (by digest).
2. Attach `sample.dump`, `sample.image`, and `sample.expected.json` to the release, and record the image's `RepoDigests` next to them.
3. Later versions run `upgrade-test.py` with each kept sample and the new image.

The self test is `server/src/test/java/io/popdesk/deploy/UpgradeHarnessTest.java`: a broken migration `V999` must make every check fail.
