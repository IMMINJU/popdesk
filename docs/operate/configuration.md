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
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`, `MAIL_FROM` | Outgoing email | None. Without them, nothing is sent and popdesk writes one log line in place of each email |
| `SMTP_ALLOW_PLAINTEXT` | `true` allows an unencrypted SMTP connection when the server does not offer STARTTLS. popdesk logs a warning at startup while it is on. Use it only for local test servers such as Mailpit | `false` |
| `NOTIFY_WEBHOOK_URL` | Team notification webhook | None. Without it, no webhook is sent |
| `POPDESK_ATTACHMENT_QUOTA` | Total size limit for all attachments | `10GB` |
| `POPDESK_STORAGE` | Where attachment files are stored: `db`, `disk`, or `s3` | `db` |
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

If the database schema is newer than the app (for example after going back to an older image), the app does not start. Restore the backup you took before upgrading.

### Health checks while starting

popdesk starts in this order: it takes the single-instance database lock, runs database migrations, creates the first admin if needed, and only then opens its ports. While migrations run, health checks cannot connect at all; this can take a long time on an upgrade.

- The Compose file gives the `app` health check a `start_period`, so connection failures during migrations do not mark it unhealthy.
- On Kubernetes, use a `startupProbe` on `/actuator/health/liveness` (port `8081`) with a generous failure threshold, then `livenessProbe` and `readinessProbe` (`/actuator/health/readiness`).
- If the first admin is not configured, the app stays running with readiness down (see `POPDESK_ADMIN_EMAIL`).

Optional observability tools are started with a second file: `docker compose -f deploy/compose.yml -f deploy/compose.obs.yml up`.

## TLS

popdesk serves plain HTTP only. Terminate TLS at a reverse proxy in front of it (ADR D-40).

- popdesk trusts `X-Forwarded-*` headers only from `POPDESK_TRUSTED_PROXIES`. If your proxy or load balancer has a public address, add it. Make sure clients cannot reach port `8080` from a trusted address without going through the proxy. If the proxy runs on the same machine, you can publish the port on the loopback address only (`127.0.0.1:8080:8080`).
- Set `POPDESK_PUBLIC_SCHEME=https` and `POPDESK_PUBLIC_PORT=443`. With `https`, cookies get the `Secure` attribute and email links use `https`.
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

### Mail servers

popdesk always encrypts its connections to the SMTP and IMAP servers unless you turn that off (ADR D-51).

- Port `465` (SMTP) or `993` (IMAP): TLS from the start of the connection (implicit TLS).
- Any other port: popdesk requires STARTTLS. If the server does not offer it, popdesk does not connect and does not fall back to plain text.
- Plain text is used only when `SMTP_ALLOW_PLAINTEXT=true` or `IMAP_ALLOW_PLAINTEXT=true`. popdesk logs a warning at startup while either is on.

### Webhook and audit copy

`NOTIFY_WEBHOOK_URL` and `AUDIT_FORWARD_URL` may use `http`, because many internal receivers do not have TLS. popdesk accepts them and logs a warning at startup (without the address). Use `https` when the receiver is outside your network.

## Commands

Run these inside the app container, for example `docker compose exec app popdesk <command>`. Each one is recorded in the audit log.

| Command | What it does |
|---|---|
| `popdesk admin reset-password <email>` | Asks twice for a new password for an admin account, without showing it, and sets it. The password is never printed or logged. It also unlocks the account and ends its sessions. It needs a terminal: run it as `docker compose exec app popdesk admin reset-password <email>` (without `-T`); without a terminal it fails. Use it when no admin can sign in |
| `popdesk backup <file>` | Writes a database dump, using the `pg_dump` that matches the server |
| `popdesk restore <file>` | Restores a dump into an empty database only, before the app starts serving |

When `POPDESK_STORAGE` is `disk` or `s3`, back up the attachment folder or bucket after the database dump.
