# Soak test

Runs popdesk the way a small team would for hours or a day, and writes down once an hour how fast it answers and how much memory and disk it takes (design/slices/b9-run/plan.md). Python 3, standard library only; needs Docker with Compose v2 on the same host as the installation, because it reads the containers.

It works against an installation you started yourself, behind its TLS proxy, as people reach it. Unlike the upgrade harness ([../upgrade/README.md](../upgrade/README.md)), it starts and removes nothing.

## What it does

1. **Setup**, through the console as the first admin: 3 customer companies (`SOAKA`, `SOAKB`, `SOAKC`), 4 agents (one per company, the fourth on all three), and 30 customers (10 per company; `customer11` is the manager of `SOAKB`). All addresses are under `soak.popdesk.test`. Each person accepts their invitation with the link from the invitation email, read from Mailpit; when SMTP is off, the console's link is used instead. On a second start, people who exist already are signed in and reused, and an invitation nobody accepted is sent again, so the tool can be stopped and started again on the same installation.
2. **Load**, until `--duration` ends or Ctrl-C: actions arrive at random, about `--rate` a second on average (default 1). Every 10 minutes or so a burst of 1 to 3 minutes comes at 4 times the quiet rate, as at the start of a working day. Each action is done by one person picked at random, with their own session cookie, and sends one to three requests:

   | Who | Actions |
   |---|---|
   | Customers | list issues (all, or their own), search with 1, 2, or 3 characters (Korean and English), view an issue and its comments, file an issue (one in five with a file of 80 to 100 KB), reply on their own issue, close their own resolved issue |
   | Agents | list issues (by status, company, due date, assignee), search with 1, 2, or 3 characters, view an issue and its comments, comment (one in four internal), move an issue one status on (to `RESOLVED` at most), take an issue |
   | Both | read the inbox: the unread count, the list, and now and then mark an item read |

   Every 15 minutes it checks each session and signs in again when one ended. A 429 is recorded like any other answer and not retried.
3. **Recording**: every request is kept in memory with its kind, time, and status. Once an hour (`--interval`) and when the run ends, one line goes to `OUT/hourly.jsonl` and the requests of that hour are dropped.

Nothing it writes or prints holds a password, a session cookie, an invitation link or token, or the text of an issue or comment. Email addresses are masked after their first character (`c***@soak.popdesk.test`).

| Exit code | Meaning |
|---|---|
| 0 | no request got a 5xx or no answer |
| 1 | at least one request got a 5xx or no answer (the lines say which) |
| 3 | the tool could not start or set up: arguments, sign-in, TLS, Docker |

## Run it

```
export SOAK_ADMIN_PASSWORD='the first admin password'
python deploy/soak/soak.py --portal https://portal.localhost --console https://console.localhost \
  --cacert root.crt --admin-email admin@soak.popdesk.test --mailpit http://127.0.0.1:8025 \
  --project soak --compose-file compose.yml --compose-file compose.soak.yml --env-file .env \
  --duration 24h --out soak-out
```

| Option | Meaning |
|---|---|
| `--portal`, `--console` | the two app addresses as people open them |
| `--cacert FILE` | the CA certificate that signed the proxy's certificate. For Caddy's `tls internal`: `docker compose cp caddy:/data/caddy/pki/authorities/local/root.crt root.crt` |
| `--insecure` | do not check the certificate at all, instead of `--cacert` |
| `--connect ADDR` | connect to this address instead of looking up the host names. A name ending in `.localhost` connects to `127.0.0.1` without it (Windows does not resolve those names) |
| `--admin-email` | the first admin. The password comes from `SOAK_ADMIN_PASSWORD` or `--admin-password-file`, never from an argument |
| `--people-password-file` | the password of the people it makes (else `SOAK_PEOPLE_PASSWORD`, else one derived from the admin password, so a second start finds the same) |
| `--mailpit URL` | Mailpit's address, to read invitation emails |
| `--project`, `--compose-file`, `--env-file` | the Compose project of the installation, as you give them to `docker compose` |
| `--duration` | how long to put load on: `600`, `10m`, `24h` (default `24h`) |
| `--rate` | actions a second on average (default `1`) |
| `--interval` | time between lines (default `1h`) |
| `--seed` | seed of the random choices (default `1`) |
| `--out DIR` | where `hourly.jsonl` goes; lines are added to it, so a second start goes on in the same file |

Setup signs in 35 people, and popdesk takes 20 sign-ins a minute from one address. Through the proxy they all come from one address, so setup waits out the limit and takes about two minutes on a second start.

## An installation to run it on

These files go into a folder of their own next to the release `compose.yml`; they are not part of the repository. Caddy runs in the Compose project with a fixed address, so that `POPDESK_TRUSTED_PROXIES` can name it alone, and port `8080` is published on the loopback address only.

`.env`:

```
POPDESK_DB_PASSWORD=choose-a-database-password
POPDESK_ADMIN_EMAIL=admin@soak.popdesk.test
POPDESK_ADMIN_PASSWORD=choose-a-long-admin-password
POPDESK_PUBLIC_SCHEME=https
POPDESK_PUBLIC_PORT=443
POPDESK_TRUSTED_PROXIES=172.30.99.10
SMTP_HOST=mailpit
SMTP_PORT=1025
SMTP_ALLOW_PLAINTEXT=true
MAIL_FROM=help@soak.popdesk.test
```

`Caddyfile`:

```caddyfile
portal.localhost, console.localhost {
	tls internal
	reverse_proxy app:8080
}
```

`compose.soak.yml`:

```yaml
services:
  app:
    ports: !override
      - "127.0.0.1:8080:8080"
  caddy:
    image: caddy:2.11.7-alpine
    restart: unless-stopped
    ports:
      - "127.0.0.1:443:443"
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy-data:/data
    networks:
      default:
        ipv4_address: 172.30.99.10
  mailpit:
    image: axllent/mailpit:v1.31.4
    restart: unless-stopped
    ports:
      - "127.0.0.1:8025:8025"
networks:
  default:
    ipam:
      config:
        - subnet: 172.30.99.0/24
volumes:
  caddy-data:
```

```
docker compose -p soak -f compose.yml -f compose.soak.yml up -d
docker compose -p soak -f compose.yml -f compose.soak.yml cp caddy:/data/caddy/pki/authorities/local/root.crt root.crt
```

`ports: !override` needs Compose v2.24 or later. The release `compose.yml` gives its volumes fixed names (`popdesk-db`, `popdesk-attachments`); next to another installation on the same host, rename them in `compose.soak.yml` (`volumes: db: name: soak-popdesk-db`). Remove it all with `down -v` when done. To back it up on a schedule meanwhile, see [Back up on a schedule](../../docs/operate/backup.md#back-up-on-a-schedule).

## How it reads the app's metrics

The management port `8081` is only on the Compose network. The tool reads `/actuator/prometheus` with `docker compose exec -T app bash -c ...` and bash's `/dev/tcp`, the same way the release Compose file's health check reads readiness, so it needs no other image and no extra container on the network. The process memory comes from `/proc/1/status` in the same container (the image runs Java as process 1).

## `hourly.jsonl`

One JSON object a line:

| Field | Content |
|---|---|
| `time` | when the line was written (UTC) |
| `from` | when the hour of this line began: the start of the load, or the previous line |
| `elapsedSeconds` | seconds since the load began |
| `requests` | requests in this hour |
| `all` | `p50Ms`, `p95Ms`, `maxMs` of every request in this hour, in milliseconds, from opening the connection (TLS included) to the last byte of the answer |
| `kinds` | the same and `count` for each kind of request, such as `portal.issue.file`, `portal.issue.file+attachment`, `console.issues.search2` (a search with 2 characters), `portal.inbox.list` |
| `statuses` | count of each status code; `0` means no answer (a connection error or a timeout) |
| `serverErrors` | count of each 5xx by status and path, with keys and IDs left out (`500 /api/console/issues/{key}/status`) |
| `clientErrors` | count of each 4xx by status and kind of request |
| `noAnswer` | count of each request without an answer, by error and path |
| `app.heapUsedBytes`, `app.heapMaxBytes` | heap in use and its limit, all heap pools together (`jvm_memory_used_bytes`, `jvm_memory_max_bytes`) |
| `app.oldGenAfterGcBytes` | the old generation after the last full or mixed collection (`jvm_gc_live_data_size_bytes`): the heap the app keeps. A line that keeps growing over the day is a leak |
| `app.jvmCommittedBytes` | memory the JVM has taken from the system for its pools, heap and non-heap |
| `app.processResidentBytes` | resident memory of the Java process (`VmRSS`) |
| `app.httpServerRequests` | the app's own request counter since it started (`http_server_requests_seconds_count`, all series together). In a 10-minute run on 0.6.0 it counted fewer requests than the tool sent, so read it as a trend, not as a total |
| `app.liveThreads` | live JVM threads |
| `containers.app`, `containers.db` | `docker stats --no-stream`: `memUsage` as Docker prints it, `memBytes`, and `cpu` |
| `dbSizeBytes` | `pg_database_size` of the popdesk database |
| `appLogLines` | lines in the app's log so far (what `docker compose logs` still holds; the release file keeps at most 5 files of 10 MB) |
| `appLogLinesThisHour` | lines the app logged since `from` |

A reading that fails is `null` in its line, and the tool goes on.
