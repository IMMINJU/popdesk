# Configuration

How to configure a popdesk installation: environment variables, the Docker Compose layout, and TLS.

popdesk is not released yet. Values here are the planned contract and may change before the first release.

## Environment variables

| Variable | Meaning | Default |
|---|---|---|
| `POPDESK_DB_URL`, `POPDESK_DB_USER`, `POPDESK_DB_PASSWORD` | Database connection | Set by the Compose file |
| `POPDESK_PORTAL_HOST`, `POPDESK_CONSOLE_HOST` | Host names of the customer portal and the agent console | `portal.localhost`, `console.localhost` |
| `POPDESK_PUBLIC_SCHEME`, `POPDESK_PUBLIC_PORT` | Scheme and port used to build links in emails | `http`, `8080` |
| `POPDESK_ADMIN_EMAIL`, `POPDESK_ADMIN_PASSWORD` | First admin, created on first start | None. Without them, the first start stops and logs what to set |
| `POPDESK_TIMEZONE` | Time zone used to judge dates (for example whether a due date has passed) | `UTC` |
| `POPDESK_LOCALE` | Default language of emails and server messages | `en` |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`, `MAIL_FROM` | Outgoing email | None. Without them, nothing is sent and popdesk writes one log line in place of each email |
| `NOTIFY_WEBHOOK_URL` | Team notification webhook | None. Without it, no webhook is sent |
| `POPDESK_ATTACHMENT_QUOTA` | Total size limit for all attachments | `10GB` |
| `POPDESK_STORAGE` | Where attachment files are stored: `db`, `disk`, or `s3` | `db` |
| `POPDESK_STORAGE_DIR` | Folder for attachments when `POPDESK_STORAGE=disk` (mount a volume here) | `/data/attachments` |
| `S3_ENDPOINT`, `S3_REGION`, `S3_BUCKET`, `S3_ACCESS_KEY`, `S3_SECRET_KEY` | S3-compatible storage when `POPDESK_STORAGE=s3` | None |
| `POPDESK_AUDIT_RETENTION` | How long audit log entries are kept (ISO-8601 duration) | `P365D` |
| `POPDESK_ISSUE_RETENTION` | How long after closing an issue it is moved to the trash | None (off) |
| `AUDIT_FORWARD_URL` | Address that receives a copy of every audit log entry | None (off) |
| `IMAP_HOST`, `IMAP_PORT`, `IMAP_USER`, `IMAP_PASSWORD`, `IMAP_FOLDER` | Incoming email | None. Without them, incoming email is off. `IMAP_FOLDER` defaults to `INBOX` |
| `MAIL_INBOUND_ADDRESS` | Address customers send issues to; used as `Reply-To` on notification emails | None |
| `OIDC_ISSUER`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET` | Sign-in with an OIDC provider | None. Without them, OIDC sign-in is off |
| `OIDC_APPS` | Which apps show the OIDC sign-in button: `console`, `portal`, or `both` | `console` |

Changing the attachment storage does not move existing files. Each attachment records where it is stored, and popdesk keeps reading old files from there.

The first admin is created only when the database has no admin. After that you can remove `POPDESK_ADMIN_PASSWORD`.

### These names are a public contract

The names and meanings of the variables above are a public contract. When one is renamed or removed:

- the release notes say so, and
- for one release, the old name is still accepted.

### Secrets

These values are secrets: `POPDESK_DB_PASSWORD`, `POPDESK_ADMIN_PASSWORD`, `SMTP_PASSWORD`, `IMAP_PASSWORD`, `NOTIFY_WEBHOOK_URL`, `S3_SECRET_KEY`, `OIDC_CLIENT_SECRET`.

- popdesk has no default for any of them.
- popdesk never writes them to the log or the database.
- Keep them in an `.env` file or your secret store, not in the Compose file.

## Docker Compose

> **Proposed, waiting for maintainer confirmation.** The names below become a public contract once confirmed.

| Item | Value |
|---|---|
| Services | `app` (popdesk), `db` (PostgreSQL 16) |
| Published ports | `app` port `8080` only. The `db` port is not published |
| Volumes | `popdesk-db` (database files), `popdesk-attachments` (attachment files when `POPDESK_STORAGE=disk`, mounted at `/data/attachments`) |
| Restart policy | `unless-stopped` for both services |

Volume names are set explicitly in the Compose file, so they do not change with the Compose project name.

Optional observability tools are started with a second file: `docker compose -f deploy/compose.yml -f deploy/compose.obs.yml up`.

## TLS

popdesk serves plain HTTP only. Terminate TLS at a reverse proxy in front of it (ADR D-40).

- popdesk trusts the `X-Forwarded-*` headers that the proxy sets. Make sure only the proxy can reach port `8080`; otherwise a client can send these headers itself. If the proxy runs on the same machine, publish the port on the loopback address only (`127.0.0.1:8080:8080`).
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
