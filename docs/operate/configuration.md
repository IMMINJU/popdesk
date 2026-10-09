# Configuration

How to configure a popdesk installation: environment variables, the Docker Compose layout, and TLS.

popdesk is not released yet. Values here are the planned contract and may change before the first release.

## Environment variables

| Variable | Meaning | Default |
|---|---|---|
| `POPDESK_DB_URL`, `POPDESK_DB_USER`, `POPDESK_DB_PASSWORD` | Database connection | Set by the Compose file. The URL includes `logServerErrorDetail=false`; keep it when you change the URL, or database errors can copy values such as email addresses into the log |
| `POPDESK_PORTAL_HOST`, `POPDESK_CONSOLE_HOST` | Host names of the customer portal and the agent console | `portal.localhost`, `console.localhost` |
| `POPDESK_PUBLIC_SCHEME`, `POPDESK_PUBLIC_PORT` | Scheme and port used to build links in emails | `http`, `8080` |
| `POPDESK_ADMIN_EMAIL`, `POPDESK_ADMIN_PASSWORD` | First admin, created on first start | None. If the database has no admin and these are missing (or the password breaks the password rules), popdesk does not serve requests: the process keeps running, logs what to set, and its health check stays unhealthy. It does not exit, so the container does not restart in a loop |
| `POPDESK_TIMEZONE` | Time zone used to judge dates (for example whether a due date has passed) | `UTC` |
| `POPDESK_LOCALE` | Default language of emails and server messages | `en` |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`, `MAIL_FROM` | Outgoing email | None. Without `SMTP_HOST`, nothing is sent and popdesk writes one log line (the address masked) in place of each email. `SMTP_PORT` defaults to `587`. Without `SMTP_USER` popdesk does not sign in to the server. Without `MAIL_FROM` the sender is `SMTP_USER` when it is an address, else `popdesk@<console host>` |
| `SMTP_ALLOW_PLAINTEXT` | `true` allows an unencrypted SMTP connection when the server does not offer STARTTLS. popdesk logs a warning at startup while it is on. Use it only for local test servers such as Mailpit | `false` |
| `NOTIFY_WEBHOOK_URL` | Team notification webhook | None. Without it, no webhook is sent |
| `POPDESK_ATTACHMENT_QUOTA` | Total size limit for all attachments, internal ones included (`MB` and `GB` are 2^20 and 2^30 bytes). Over 80%, popdesk logs a warning naming this setting and puts an alert in every admin's inbox | `10GB` |
| `POPDESK_STORAGE` | Where attachment files are stored: `db`, `disk`, or `s3`. This version stores in `db` only; with `disk` or `s3` popdesk does not start, so that files do not land somewhere a backup of the folder or bucket would miss | `db` |
| `POPDESK_STORAGE_DIR` | Folder for attachments when `POPDESK_STORAGE=disk` (mount a volume here) | `/data/attachments` |
| `S3_ENDPOINT`, `S3_REGION`, `S3_BUCKET`, `S3_ACCESS_KEY`, `S3_SECRET_KEY` | S3-compatible storage when `POPDESK_STORAGE=s3` | None |
| `POPDESK_AUDIT_RETENTION` | How long audit log entries are kept (ISO-8601 duration). At least `P30D`; a shorter value stops startup | `P365D` |
| `POPDESK_ISSUE_RETENTION` | How long after closing an issue it is moved to the trash. At least `P90D`; a shorter value stops startup | None (off) |
| `AUDIT_FORWARD_URL` | Address that receives a copy of every audit log entry | None (off) |
| `IMAP_HOST`, `IMAP_PORT`, `IMAP_USER`, `IMAP_PASSWORD`, `IMAP_FOLDER` | Incoming email | None. Without them, incoming email is off. `IMAP_FOLDER` defaults to `INBOX` |
| `IMAP_ALLOW_PLAINTEXT` | `true` allows an unencrypted IMAP connection when the server does not offer STARTTLS. popdesk logs a warning at startup while it is on | `false` |
| `IMAP_PROCESSED_FOLDER` | Folder that processed email is moved to. popdesk reads every message in `IMAP_FOLDER`, so use a mailbox that only popdesk reads | `popdesk-processed` |
| `IMAP_QUARANTINE_FOLDER` | Folder for email that failed 5 times or went over the rate limit | `popdesk-quarantine` |
| `MAIL_INBOUND_ADDRESS` | Address customers send issues to; used as `Reply-To` on notification emails | None |
| `OIDC_ISSUER`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET` | Sign-in with an OIDC provider | None. Without them, OIDC sign-in is off |
| `OIDC_APPS` | Which apps show the OIDC sign-in button: `console`, `portal`, or `both` | `console` |
| `POPDESK_TRUSTED_PROXIES` | Addresses (IPs or CIDR ranges, comma-separated) whose `X-Forwarded-*` headers popdesk trusts | Loopback and private ranges (`127.0.0.0/8`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `::1`, `fc00::/7`) |
| `POPDESK_INSTANCE_LOCK_WAIT` | How long a starting instance waits for the single-instance database lock before it stops with a message (ISO-8601 duration). Raise it if a restarted container starts before the old one has let go | `PT30S` |
| `POPDESK_SANDBOX` | `true` for a copy of a real installation: all email goes to `POPDESK_SANDBOX_MAIL_TO` only, and incoming email, the webhook, and the audit copy are off | `false` |
| `POPDESK_SANDBOX_MAIL_TO` | The only address that receives email when `POPDESK_SANDBOX=true` | None (with sandbox on and no address, nothing is sent) |

Changing the attachment storage does not move existing files. Each attachment records where it is stored, and popdesk keeps reading old files from there.

The first admin is created only when the database has no admin. After that you can remove `POPDESK_ADMIN_PASSWORD`.

### These names are a public contract

The names and meanings of the variables above are a public contract. When one is renamed or removed:

- the release notes say so, and
- for one release, the old name is still accepted.

### Secrets

These values are secrets: `POPDESK_DB_PASSWORD`, `POPDESK_ADMIN_PASSWORD`, `SMTP_PASSWORD`, `IMAP_PASSWORD`, `NOTIFY_WEBHOOK_URL`, `S3_SECRET_KEY`, `OIDC_CLIENT_SECRET`.

- popdesk has no default for any of them.
- Each can also be given as a file: set `<NAME>_FILE` to a path (for example `POPDESK_DB_PASSWORD_FILE=/run/secrets/db_password`) and popdesk reads the value from it. Setting both is an error. One line break at the end of the file (`\n` or `\r\n`) is removed, because most editors and secret tools add one; anything else in the file, including other whitespace, is part of the value. Use this for Docker secrets, or when a value contains `$`, which Compose would otherwise substitute.
- popdesk never writes them to the log or the database.
- Keep them in an `.env` file or your secret store, not in the Compose file.

## Docker Compose

These names and values are a public contract (ADR D-45).

| Item | Value |
|---|---|
| Services | `app` (popdesk), `db` (PostgreSQL 16; the major version is fixed per release) |
| Published ports | `app` port `8080` only, on all interfaces. The `db` port and the management port are not published |
| Management port | `8081` inside the Compose network: health checks (`/actuator/health`) and metrics (`/actuator/prometheus`). Not served on `8080` |
| Volumes | `popdesk-db` (database files), `popdesk-attachments` (attachment files when `POPDESK_STORAGE=disk`, mounted at `/data/attachments`) |
| Restart policy | `unless-stopped` for both services |
| Logs | JSON lines on standard output, with a size limit per container (`max-size: 10m`, `max-file: 5`) |
| Images | Each release publishes `amd64` and `arm64` images. The Compose file attached to a release points at that exact version; do not use `latest` |

Volume names are set explicitly in the Compose file, so they do not change with the Compose project name.

Get the Compose file from the release you install, not from the `main` branch.

popdesk runs as one app instance (ADR D-23). On start it takes a database lock; a second instance waits briefly and then stops with a message. On Kubernetes, use the `Recreate` strategy.

If the database schema is newer than the app (for example after going back to an older image), the app does not start. Restore the backup you took before upgrading. If a migration fails, the app does not start either; [upgrade.md](upgrade.md) has the recovery steps.

The database user must be allowed to create tables in the `public` schema of its database (`GRANT CREATE ON SCHEMA public TO <user>`; on PostgreSQL 15 and later only the database owner has it by default). Without it popdesk logs the missing privilege and does not start.

### Health checks while starting

popdesk starts in this order: it takes the single-instance database lock, runs database migrations, creates the first admin if needed, and only then opens its ports. While migrations run, health checks cannot connect at all; this can take a long time on an upgrade.

- The Compose file's `app` health check asks `/actuator/health/readiness` on port `8081`. It has `start_period: 3m` (checked every 2 seconds meanwhile), so connection failures during migrations do not mark it unhealthy, and then `interval: 10s` with `retries: 6`. An app that is not ready by then (for example without a first admin) turns unhealthy within 5 minutes. If an upgrade's migrations take longer than 3 minutes, raise `start_period` in your copy of the Compose file; the release notes say when a migration may be slow.
- On Kubernetes, use a `startupProbe` on `/actuator/health/liveness` (port `8081`) with a generous failure threshold, then `livenessProbe` and `readinessProbe` (`/actuator/health/readiness`):

  ```yaml
  startupProbe:
    httpGet: { path: /actuator/health/liveness, port: 8081 }
    periodSeconds: 5
    failureThreshold: 360   # up to 30 minutes for migrations
  livenessProbe:
    httpGet: { path: /actuator/health/liveness, port: 8081 }
    periodSeconds: 10
  readinessProbe:
    httpGet: { path: /actuator/health/readiness, port: 8081 }
    periodSeconds: 10
  ```

- If the first admin is not configured, the app stays running with readiness down (see `POPDESK_ADMIN_EMAIL`).

### Memory and JVM options

The image sets its JVM options itself, so the app behaves the same on every host: the G1 garbage collector and a heap of at most 384 MB. Measured before the first release, on a development machine: the app container used about 0.4 GB right after start and up to about 0.6 GB with 20 transfers of 10 MB at once; PostgreSQL up to about 0.35 GB. To change the heap, set `JDK_JAVA_OPTIONS` (for example `JDK_JAVA_OPTIONS=-Xmx768m`); an `-Xmx` there wins over the image's default.

### More Compose files

These go on top of the release file, which stays as it is:

- `deploy/compose.obs.yml`: Grafana LGTM (Grafana, Prometheus, Loki, Tempo in one container) that reads the app's metrics from port `8081` and shows the dashboards in `deploy/grafana/`. Start it with `docker compose -f deploy/compose.yml -f deploy/compose.obs.yml up -d`; Grafana is on `http://127.0.0.1:3000`. It needs about 1.5 GB of memory more.
- `deploy/compose.dev.yml`: for development only. Adds Mailpit and points SMTP at it in plain text (`SMTP_ALLOW_PLAINTEXT=true`); its web page is on `http://127.0.0.1:8025`.

## TLS

popdesk serves plain HTTP only. Terminate TLS at a reverse proxy in front of it (ADR D-40).

- popdesk trusts `X-Forwarded-*` headers only from `POPDESK_TRUSTED_PROXIES`. If your proxy or load balancer has a public address, add it. Make sure clients cannot reach port `8080` from a trusted address without going through the proxy. If the proxy runs on the same machine, you can publish the port on the loopback address only (`127.0.0.1:8080:8080`).
- Set `POPDESK_PUBLIC_SCHEME=https` and `POPDESK_PUBLIC_PORT=443`. With `https`, cookies get the `Secure` attribute and email links use `https`.
- The proxy must pass the original host (`Host` or `X-Forwarded-Host`). A request for any other host gets a plain text 404 that names the two configured hosts and the host it came for.
- When a trusted proxy forwards a scheme or port (`X-Forwarded-Proto`, `X-Forwarded-Port`, or a port in `X-Forwarded-Host`) other than `POPDESK_PUBLIC_SCHEME` and `POPDESK_PUBLIC_PORT`, popdesk logs one warning for each such pair. Links in emails always use the configured values, so fix the setting the warning names.
- Traffic between `app` and `db` stays on the Compose internal network and is not encrypted. If you run the database elsewhere, add `sslmode` to `POPDESK_DB_URL` (for example `?sslmode=verify-full`).

Example with [Caddy](https://caddyserver.com/), which gets certificates automatically and sets the `X-Forwarded-*` headers:

```caddyfile
portal.example.com, console.example.com {
	reverse_proxy 127.0.0.1:8080
}
```

with

```
POPDESK_PORTAL_HOST=portal.example.com
POPDESK_CONSOLE_HOST=console.example.com
POPDESK_PUBLIC_SCHEME=https
POPDESK_PUBLIC_PORT=443
```

### Request body size at the proxy

A new issue can carry up to 50 MB of files, and popdesk takes requests of up to 55 MB (see [Attachments](#attachments)). Give the proxy a body limit of at least 55 MB. With a lower limit the proxy answers `413` itself; the portal then shows that the file hit the size limit in front of the server, but the customer cannot attach it.

| Proxy | Default limit | Setting |
|---|---|---|
| Caddy | none | Nothing to do. If you set one, use `request_body { max_size 55MB }` inside the site block |
| nginx | 1 MB | `client_max_body_size 55m;` in the `server` (or `location`) block. Also set `proxy_request_buffering off;` so large uploads are not written to nginx's disk first |
| Traefik | none | Nothing to do. If you add the `buffering` middleware, set `maxRequestBodyBytes: 57671680` (55 MB) |

nginx example:

```nginx
server {
    listen 443 ssl;
    server_name portal.example.com console.example.com;
    client_max_body_size 55m;
    proxy_request_buffering off;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

### Mail servers

popdesk always encrypts its connections to the SMTP and IMAP servers unless you turn that off (ADR D-51).

- Port `465` (SMTP) or `993` (IMAP): TLS from the start of the connection (implicit TLS).
- Any other port: popdesk requires STARTTLS. If the server does not offer it, popdesk does not connect and does not fall back to plain text.
- Plain text is used only when `SMTP_ALLOW_PLAINTEXT=true` or `IMAP_ALLOW_PLAINTEXT=true`. popdesk logs a warning at startup while either is on.

### Webhook and audit copy

`NOTIFY_WEBHOOK_URL` and `AUDIT_FORWARD_URL` may use `http`, because many internal receivers do not have TLS. popdesk accepts them and logs a warning at startup (without the address). Use `https` when the receiver is outside your network.

## Sending email and webhook messages

Emails and webhook messages wait in a queue in the database and are sent by the app in the background, oldest first. The address, the language, and the text are read when a message is sent, not when it is queued.

- A message that fails is tried again after 1 second, then 2, 4, and so on, at most every 10 minutes. 24 hours after its first failure it is given up: it stays in the queue as failed, every admin gets an item in the console inbox, and it is deleted 7 days later.
- Admins see, per channel, whether it is set up, the last successful send, and the queued and failed messages in the console's system status. The metrics `popdesk_outbox_queued`, `popdesk_outbox_failed`, and `popdesk_outbox_sent_total` (label `channel`) show the same.
- An invitation email gets a new link each time the app restarts before it was sent; the link of an earlier failed attempt then stops working.
- Any `2xx` answer from the webhook receiver counts as sent. Redirects are not followed.

## Attachments

- One file is at most 10 MB, and the public attachments of one issue (on the issue and its public comments) at most 50 MB. Attachments of internal comments count only against `POPDESK_ATTACHMENT_QUOTA`.
- The app takes requests of up to 55 MB with files of up to 11 MB, so a file just over 10 MB gets a clear error instead of a cut connection. Received files go to temporary files, never into memory. A reverse proxy in front needs a body limit of at least 55 MB ([Request body size at the proxy](#request-body-size-at-the-proxy)). Up to 16 uploads at once can hold up to 55 MB each in the container's `/tmp` for a moment.
- At most 16 uploads and, apart from them, 16 downloads run at once. The next one gets 503 `SERVER_BUSY` with `Retry-After`; clients try again.
- A deleted attachment keeps its row as a trace without the file name. Its file is removed at once; if that fails, the delete still succeeds and the daily attachment cleanup removes the file.
- The daily cleanup (`attachment-cleanup`) also ends uploads that never finished: a file still pending after an hour is removed. It works from the database rows only and never lists the storage.
- The metric `popdesk_attachments_bytes` is the total that `POPDESK_ATTACHMENT_QUOTA` limits.

## Commands

The `popdesk` command is in the app image. Each one is recorded in the audit log, with popdesk itself (account `0`) as the person. A command that fails changes nothing and ends with a non-zero exit code.

| Command | What it does |
|---|---|
| `popdesk admin reset-password <email>` | Asks twice for a new password for an admin account, without showing it, and sets it. The password must keep the password rules; the command does not ask again. The password is never printed or logged. It also unlocks the account and ends its sessions. It needs a terminal: run it as `docker compose exec app popdesk admin reset-password <email>` (without `-T`); without a terminal it fails. Use it when no admin can sign in |
| `popdesk backup <file>` | Writes a database dump in `pg_dump`'s custom format, while the app runs. The image's `pg_dump` has the same major version as the `db` image of its release; if the server has another major version, the command stops |
| `popdesk restore <file>` | Restores a dump into an empty database only, before the app starts serving. If the app is running on the database (it holds the single-instance lock) or the database has any table, it stops |

### Back up

Write the dump inside the running app container, then copy it out:

```
docker compose exec app popdesk backup /tmp/popdesk.dump
docker compose cp app:/tmp/popdesk.dump ./popdesk-$(date +%F).dump
docker compose exec app rm /tmp/popdesk.dump
```

Keep the dump somewhere other than the Docker host. When `POPDESK_STORAGE` is `disk` or `s3`, back up the attachment folder or bucket after the database dump.

### Restore

Restore into a new, empty database with the app stopped. Run the command in a new container of the app service and mount the dump into it as one file (`docker compose cp` cannot copy into a container that `run` starts):

```
docker compose stop app
docker compose rm -f -s db
docker volume rm popdesk-db
docker compose up -d db
docker compose run --rm -v "$PWD/popdesk-2026-01-31.dump:/tmp/restore.dump:ro" app popdesk restore /tmp/restore.dump
docker compose up -d
```

Removing the `popdesk-db` volume deletes the current database; take a backup of it first if you may need it. The restore runs in one transaction: if it fails, the database stays empty.
