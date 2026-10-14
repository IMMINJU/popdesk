# Configuration

How to configure a popdesk installation: environment variables, the Docker Compose layout, and TLS.

popdesk is not released yet. Values here are the planned contract and may change before the first release.

## Environment variables

| Variable | Meaning | Default |
|---|---|---|
| `POPDESK_DB_URL`, `POPDESK_DB_USER`, `POPDESK_DB_PASSWORD` | Database connection | Set by the Compose file. The URL includes `logServerErrorDetail=false`, which keeps values such as email addresses from database errors out of the log. The app also sets it on its own connections, so a URL without it is still safe; a URL that says `logServerErrorDetail=true` turns it off |
| `POPDESK_PORTAL_HOST`, `POPDESK_CONSOLE_HOST` | Host names of the customer portal and the agent console | `portal.localhost`, `console.localhost` |
| `POPDESK_PUBLIC_SCHEME`, `POPDESK_PUBLIC_PORT` | Scheme and port used to build links in emails | `http`, `8080` |
| `POPDESK_ADMIN_EMAIL`, `POPDESK_ADMIN_PASSWORD` | First admin, created on first start | None. If the database has no admin and these are missing (or the password breaks the password rules), popdesk does not serve requests: the process keeps running, logs what to set, and its health check stays unhealthy. It does not exit, so the container does not restart in a loop |
| `POPDESK_TIMEZONE` | Time zone used to judge dates (for example whether a due date has passed). The test email gives the time it was sent in this zone | `UTC` |
| `POPDESK_LOCALE` | Default language of emails and server messages: `en` or `ko`. Upper case and spaces around the value are accepted (`KO` is `ko`) | `en` when the variable is not set. With an empty value (`POPDESK_LOCALE=`) or any other value, such as `ko-KR`, popdesk does not serve requests, as with a missing first admin: the process keeps running, logs the name of the setting, and its health check stays unhealthy |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`, `MAIL_FROM` | Outgoing email | None. Without `SMTP_HOST`, nothing is sent and popdesk writes one log line (the address masked) in place of each email. A password reset request is still accepted then, but its email does not go out; on such an installation an admin's password reset shows the reset link on the screen once. `SMTP_PORT` defaults to `587`. Without `SMTP_USER` popdesk does not sign in to the server. Without `MAIL_FROM` the sender is `SMTP_USER` when it is an address, else `popdesk@<console host>` |
| `SMTP_ALLOW_PLAINTEXT` | `true` allows an unencrypted SMTP connection when the server does not offer STARTTLS. popdesk logs a warning at startup while it is on. Use it only for local test servers such as Mailpit | `false` |
| `NOTIFY_WEBHOOK_URL` | Team notification webhook | None. Without it, no webhook is sent. Also off while `POPDESK_SANDBOX=true` |
| `POPDESK_ATTACHMENT_QUOTA` | Total size limit for all attachments, internal ones included (`MB` and `GB` are 2^20 and 2^30 bytes). Over 80%, popdesk logs a warning naming this setting and puts an alert in every admin's inbox | `10GB` |
| `POPDESK_STORAGE` | Where new attachment files are stored: `db` (the database), `disk` (a folder), or `s3` (a bucket). With any other value popdesk does not start. See [Attachment storage](#attachment-storage) | `db` |
| `POPDESK_STORAGE_DIR` | Folder for attachments when `POPDESK_STORAGE=disk`: an absolute path, with a volume mounted there. With a relative path popdesk does not start | `/data/attachments` |
| `S3_BUCKET`, `S3_ACCESS_KEY`, `S3_SECRET_KEY` | The bucket and the key pair when `POPDESK_STORAGE=s3`. All three are required then; the key pair is also required while any attachment is still stored in S3. popdesk takes its credentials from these two keys only, never from `AWS_` variables, a profile file, or an instance role | None |
| `S3_ENDPOINT`, `S3_REGION` | Where the bucket is. With `S3_ENDPOINT` (an S3-compatible server) requests go to that address in path style, `https://host/bucket/key`, and `S3_REGION` is optional (`us-east-1` is used to sign). Without `S3_ENDPOINT` requests go to AWS in the region `S3_REGION`. One of the two is required. An `http` address is taken, with a warning in the log at start: the files and the signed requests then travel unencrypted | None |
| `POPDESK_AUDIT_RETENTION` | How long audit log entries are kept: an ISO-8601 duration of days and time, such as `P365D` or `PT720H` (no years or months), at least `P30D`. Older entries are deleted once a day, and only from the start of the log as far as old entries follow one another ([audit-log.md](audit-log.md#how-long-entries-are-kept)) | `P365D` when the variable is not set. With a shorter value, an empty value, or one that is not such a duration, popdesk does not serve requests and deletes no entry, as with a missing first admin: the process keeps running, logs the name of the setting, and its health check stays unhealthy |
| `POPDESK_ISSUE_RETENTION` | How long after closing an issue it is moved to the trash. At least `P90D`; a shorter value stops startup | None (off) |
| `AUDIT_FORWARD_URL` | Address that receives a copy of every audit log entry written from then on, as an HTTP `POST` ([audit-log.md](audit-log.md#a-copy-outside-popdesk)) | None (off). Also off while `POPDESK_SANDBOX=true` |
| `AUDIT_FORWARD_TOKEN` | Token for the receiver of the audit copy: sent as `Authorization: Bearer <token>` with every entry | None. Without it, no `Authorization` header is sent |
| `IMAP_HOST`, `IMAP_PORT`, `IMAP_USER`, `IMAP_PASSWORD`, `IMAP_FOLDER` | Incoming email | None. Without them, incoming email is off. `IMAP_FOLDER` defaults to `INBOX` |
| `IMAP_ALLOW_PLAINTEXT` | `true` allows an unencrypted IMAP connection when the server does not offer STARTTLS. popdesk logs a warning at startup while it is on | `false` |
| `IMAP_PROCESSED_FOLDER` | Folder that processed email is moved to. popdesk reads every message in `IMAP_FOLDER`, so use a mailbox that only popdesk reads | `popdesk-processed` |
| `IMAP_QUARANTINE_FOLDER` | Folder for email that failed 5 times or went over the rate limit | `popdesk-quarantine` |
| `MAIL_INBOUND_ADDRESS` | Address customers send issues to; used as `Reply-To` on notification emails | None |
| `OIDC_ISSUER`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET` | Sign-in with an OIDC provider | None. Without them, OIDC sign-in is off |
| `OIDC_APPS` | Which apps show the OIDC sign-in button: `console`, `portal`, or `both` | `console` |
| `POPDESK_TRUSTED_PROXIES` | Addresses (IPs or CIDR ranges, comma-separated) whose `X-Forwarded-*` headers popdesk trusts | Loopback and private ranges (`127.0.0.0/8`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `::1`, `fc00::/7`) |
| `POPDESK_INSTANCE_LOCK_WAIT` | How long a starting instance waits for the single-instance database lock before it stops with a message (ISO-8601 duration). Raise it if a restarted container starts before the old one has let go | `PT30S` |
| `POPDESK_SANDBOX` | `true` for a copy of a real installation ([A sandbox](#a-sandbox)): the webhook and the audit copy are off, and email goes to `POPDESK_SANDBOX_MAIL_TO` only, or nowhere without it. Only the value `true` counts, in any case and with spaces around it; `1`, `yes`, and an empty value are not a sandbox. Incoming email is not turned off | `false` |
| `POPDESK_SANDBOX_MAIL_TO` | The one address that receives every email while `POPDESK_SANDBOX=true`. Read only in a sandbox | None. A sandbox without it, or with an empty value, sends no email at all. With a value that is not one email address (two addresses with a comma between them are not), a sandbox does not start, and the log names this setting without its value |

Changing the attachment storage does not move existing files, and there is no tool that moves them. Each attachment records where it is stored, and popdesk keeps reading and removing old files there ([Attachment storage](#attachment-storage)).

The first admin is created only when the database has no admin. After that you can remove `POPDESK_ADMIN_PASSWORD`.

### These names are a public contract

The names and meanings of the variables above are a public contract. When one is renamed or removed:

- the release notes say so, and
- for one release, the old name is still accepted.

### Secrets

These values are secrets: `POPDESK_DB_PASSWORD`, `POPDESK_ADMIN_PASSWORD`, `SMTP_PASSWORD`, `IMAP_PASSWORD`, `NOTIFY_WEBHOOK_URL`, `S3_SECRET_KEY`, `OIDC_CLIENT_SECRET`, `AUDIT_FORWARD_URL`, `AUDIT_FORWARD_TOKEN`.

- popdesk has no default for any of them.
- Each can also be given as a file: set `<NAME>_FILE` to a path (for example `POPDESK_DB_PASSWORD_FILE=/run/secrets/db_password`) and popdesk reads the value from it. Setting both is an error. One line break at the end of the file (`\n` or `\r\n`) is removed, because most editors and secret tools add one; anything else in the file, including other whitespace, is part of the value. Use this for Docker secrets, or when a value contains `$`, which Compose would otherwise substitute.
- popdesk never writes them to the log or the database.
- Keep them in an `.env` file or your secret store, not in the Compose file.

## Docker Compose

These names and values are a public contract (ADR D-45).

| Item | Value |
|---|---|
| Requires | Docker Engine 25 or later (the health checks use `start_interval`) with Compose v2 |
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

### A database you run yourself

The Compose file's `db` service needs none of this. With your own PostgreSQL, or a managed one:

- **The `pg_trgm` extension.** The search of issues and accounts uses it. popdesk creates it on its first start (`CREATE EXTENSION IF NOT EXISTS pg_trgm`), which works when its database user owns the database or has the `CREATE` privilege on it. Where the user may not, popdesk does not start and logs the statement and the database name; have a database administrator run `CREATE EXTENSION pg_trgm;` in that database once, then start popdesk again. The extension ships with PostgreSQL's contrib package; a PostgreSQL without it needs that package first.
- **Restore.** `popdesk restore` also works when an administrator created the extension, so that the app's user does not own it. It does not restore comments on database objects. The user still has to be able to create the extension, or the extension has to be there before the restore: in an empty database, create it first.
- **The locale of the database.** Create the database with a UTF-8 locale such as `en_US.UTF-8` (the `LC_CTYPE` setting). In a database whose `LC_CTYPE` is `C`, PostgreSQL makes no trigrams of Korean and other non-ASCII text, so a search for such text reads every issue instead of the index. The results are the same; it only gets slow as issues add up. `SELECT datctype FROM pg_database WHERE datname = current_database();` shows the setting, and it cannot be changed without making the database again.
- **Size.** The text the search looks in and its index take more space than the issues themselves: with 100,000 issues of about 2,000 characters the issue table went from 279 MB to 722 MB ([upgrade.md](upgrade.md#upgrading-to-030)).

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
- The upgrade to 0.2.0 computes a hash for every audit entry before the ports open; [upgrade.md](upgrade.md#upgrading-to-020) says how long that takes.

### Memory and JVM options

The image sets its JVM options itself, so the app behaves the same on every host: the G1 garbage collector and a heap of at most 384 MB. Measured before the first release, on a development machine: the app container used about 0.4 GB right after start and up to about 0.6 GB with 20 transfers of 10 MB at once; PostgreSQL up to about 0.35 GB. To change the heap, set `JDK_JAVA_OPTIONS` (for example `JDK_JAVA_OPTIONS=-Xmx768m`); an `-Xmx` there wins over the image's default. The heap does not shrink to a container memory limit, so if you set one (`mem_limit`, Kubernetes `resources.limits.memory`), give the app at least 0.75 GB or lower `-Xmx` with it.

Checking or setting a password computes an Argon2id hash that holds 19 MiB of heap while it runs. At most 4 are computed at once (76 MiB), a fixed limit without a setting; when many people sign in at the same moment, the rest wait their turn and get their answer a little later instead of an error.

### More Compose files

These go on top of the release file, which stays as it is:

- `deploy/compose.obs.yml`: Grafana LGTM (Grafana, Prometheus, Loki, Tempo in one container) that reads the app's metrics from port `8081` and shows the dashboards in `deploy/grafana/`. Start it with `docker compose -f deploy/compose.yml -f deploy/compose.obs.yml up -d`; Grafana is on `http://127.0.0.1:3000`. It needs about 1.5 GB of memory more.
- `deploy/compose.dev.yml`: for development only. Adds Mailpit and points SMTP at it in plain text (`SMTP_ALLOW_PLAINTEXT=true`); its web page is on `http://127.0.0.1:8025`.

## TLS

popdesk serves plain HTTP only. Terminate TLS at a reverse proxy in front of it (ADR D-40).

- popdesk trusts `X-Forwarded-*` headers only from `POPDESK_TRUSTED_PROXIES`. If your proxy or load balancer has a public address, add it. An entry that is not an IP address or a CIDR range stops popdesk from starting, and the error names the key; host names are not looked up. Make sure clients cannot reach port `8080` from a trusted address without going through the proxy. If the proxy runs on the same machine, you can publish the port on the loopback address only (`127.0.0.1:8080:8080`).
- The default trusts every private range. Without a proxy, a client on your office network, or any client when Docker Desktop or rootless Docker shows outside traffic as coming from a private gateway address, can set `X-Forwarded-For` and get around the sign-in rate limit per address. Without a proxy, set `POPDESK_TRUSTED_PROXIES` to an address nothing connects from (for example `127.0.0.1`); with a proxy, set it to the proxy's address only.
- Use TLS 1.2 or later at the proxy and let it send `Strict-Transport-Security`. popdesk receives plain HTTP from the proxy, so it does not add that header itself.
- Set `POPDESK_PUBLIC_SCHEME=https` and `POPDESK_PUBLIC_PORT=443`. With `https`, cookies get the `Secure` attribute and email links use `https`.
- Search text travels in the address (`/api/portal/issues?q=...`), so the access log of your proxy records what people search for, which can be personal data such as a name or a phone number. popdesk itself logs no search text. Leave the query string out of the proxy's access log, or keep that log as carefully and as briefly as other personal data.
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

When `AUDIT_FORWARD_URL` uses `http` and `AUDIT_FORWARD_TOKEN` is set, popdesk logs one more warning (without the token): the token is then sent unencrypted with every entry.

## Sending email and webhook messages

Emails, webhook messages, and the copies of audit entries (`AUDIT_FORWARD_URL`) wait in a queue in the database and are sent by the app in the background, oldest first. Each of the three has its own sender, so a slow receiver of one does not hold up the others. The address, the language, and the text are read when a message is sent, not when it is queued.

- A message that fails is tried again after 1 second, then 2, 4, and so on, at most every 10 minutes. 24 hours after its first failure it is given up: it stays in the queue as failed, every admin gets an item in the console inbox, and it is deleted 7 days later.
- Admins see, per channel, whether it is on, the last successful send, and the queued and failed messages in the console's system status. A channel is on when it is set up and the sandbox did not turn it off. The metrics `popdesk_outbox_queued`, `popdesk_outbox_failed`, and `popdesk_outbox_sent_total` (label `channel`: `mail`, `webhook`, `audit_forward`) show the same.
- **Sending a failed message again.** In the system status an admin can send a failed message of any of the three channels again. It goes back into the queue as a new message: it is sent at once, and when it fails again it is tried for another 24 hours. What popdesk checks before every email is checked again, so an email whose recipient was suspended meanwhile, or may no longer see the issue, is dropped and not sent. A failed message of a channel that is off cannot be sent again.
- **When a channel only fails.** When a message has waited over an hour since its first failure and the channel sent nothing in that hour, every admin gets an item in the console inbox: `MAIL_STALLED`, `WEBHOOK_STALLED`, or `AUDIT_FORWARD_STALLED`. The item comes again every 24 hours until the channel sends one message. A channel that is off raises none.
- **At most 50 emails an hour to one address.** The 51st email to an address within 60 minutes is not sent. Its message is failed at once, with the error kind `RATE_LIMITED`, and is not tried again by itself; send it again from the system status once the hour has passed. The limit stops a loop that went wrong and cannot be changed. The count is kept in memory, so it starts at none when popdesk restarts. The metric `popdesk_mail_rate_limited_total` counts the emails that were stopped.
- **Test email.** In the system status an admin can send a test email to their own address. It is sent within the request, and the page shows whether the SMTP server took it and, when it refused, what it answered, without the SMTP user name and password. An admin can send 5 test emails a minute.
- The system status also lists every cleanup job with the time of its last run and how many rows that run deleted.
- An invitation email gets a new link each time the app restarts before it was sent; the link of an earlier failed attempt then stops working.
- Any `2xx` answer from the receiver of a webhook message or an audit copy counts as sent. Redirects are not followed.

### A sandbox

A copy of your real database, started with your real settings, would email your real customers and post to your real webhook and audit receiver. Start such an installation with `POPDESK_SANDBOX=true`:

- **Email goes to one address.** Every email, invitations, password resets, and the test email included, goes to `POPDESK_SANDBOX_MAIL_TO` in place of its recipient. Its subject starts with `[sandbox]`, and the first line of its text names the account ID of the person it was for, never their address. Whether an email is sent at all is still decided for that person: an email to a suspended account is not sent to the sandbox address either.
- **Without `POPDESK_SANDBOX_MAIL_TO` no email is sent**, although `SMTP_HOST` is set. popdesk writes one log line per email, the address masked, as without `SMTP_HOST`, and invitations show their link on the screen.
- **The webhook and the audit copy are off.** Nothing is queued for them, and what was waiting in the copied database is dropped without being sent ([audit-log.md](audit-log.md#a-test-installation-from-a-copy-of-the-real-database)).
- **The 50 emails an hour count the sandbox address**, so all email of the installation together is 50 an hour. An email over that shows as failed with `RATE_LIMITED`.
- popdesk logs one warning at startup that says what is off and where email goes, the address masked after its first character. The system status says that this is a sandbox and whether it has an address; it never shows the address.
- Incoming email is not turned off. Leave the `IMAP_*` settings empty on a sandbox.

## Attachments

- One file is at most 10 MB, and the public attachments of one issue (on the issue and its public comments) at most 50 MB. Attachments of internal comments count only against `POPDESK_ATTACHMENT_QUOTA`.
- The app takes requests of up to 55 MB with files of up to 11 MB, so a file just over 10 MB gets a clear error instead of a cut connection. Received files go to temporary files, never into memory. A reverse proxy in front needs a body limit of at least 55 MB ([Request body size at the proxy](#request-body-size-at-the-proxy)). Up to 16 uploads at once can hold up to 55 MB each in the container's `/tmp` for a moment.
- At most 16 uploads and, apart from them, 16 downloads run at once. The next one gets 503 `SERVER_BUSY` with `Retry-After`; clients try again.
- A deleted attachment keeps its row as a trace without the file name. Its file is removed at once; if that fails, the delete still succeeds and the daily attachment cleanup removes the file.
- The daily cleanup (`attachment-cleanup`) also ends uploads that never finished: a file still pending after an hour is removed. It works from the database rows only and never lists the storage.
- The portal and the console send an `Idempotency-Key` with each new issue, so a filing sent twice makes one issue. popdesk keeps each key for 24 hours (table `issue_idempotency`: the account, the key, a hash of the fields, the issue); the hourly job `idempotency-key-cleanup` deletes older ones and shows in the cleanup metrics (`popdesk_cleanup_last_run_seconds`, `popdesk_cleanup_deleted`, label `job`). There is no setting for it.
- The metric `popdesk_attachments_bytes` is the total that `POPDESK_ATTACHMENT_QUOTA` limits.
- A new issue carries at most 20 files; with more the request gets 413. Each file of a new issue counts toward the account's 30 uploads in 60 seconds.
- An image in the content of an issue or a comment (PNG, JPEG, GIF, or WebP) is uploaded first, as a temporary image, and becomes an attachment of the issue when the content is saved. Until then only the account that uploaded it can read it. A temporary image that is not saved with a content within 24 hours is deleted, file and row, without a trace. One account has at most 20 temporary images and 50 MB of them at a time. There is no setting for these numbers.
- Temporary images count against `POPDESK_ATTACHMENT_QUOTA` like every other file, and their uploads and downloads share the 16 places and the 30 uploads in 60 seconds with attachments. Once saved, an image counts toward the 50 MB of its issue unless it is on an internal comment.
- The job `temporary-image-cleanup` runs every 10 minutes and deletes temporary images older than 24 hours. It shows in the system status and in the cleanup metrics like the other jobs. When a file cannot be removed, its row stays and the next run tries again; nobody reads the image in between.
- The history of an issue records each attachment that is added to it or deleted, by ID and without the file name.

### Attachment storage

`POPDESK_STORAGE` says where new files go. Whatever it is, the app itself serves every download after its permission checks; it never sends a client to the folder or the bucket and makes no signed URL.

| Storage | Where a file is | What the attachment records |
|---|---|---|
| `db` | The table `attachment_content` | `db` |
| `disk` | `POPDESK_STORAGE_DIR/{first two characters of the ID}/{ID}` | `disk` and the folder's absolute path |
| `s3` | The object `popdesk/{ID}` in `S3_BUCKET` | `s3` and `{bucket}/popdesk` (not the address: the same bucket reached at another `S3_ENDPOINT` is still read) |

A file's path or key is made of the attachment's ID only, never of the name it was uploaded with.

- **Mount a volume at the folder.** The release Compose file mounts `popdesk-attachments` at `/data/attachments`. If you set another `POPDESK_STORAGE_DIR`, mount a volume there yourself: files written into the container's own file system are gone when the container is made again. The folder must be writable by the user the app runs as (ID `10001` in the image).
- **Keep the bucket private.** popdesk checks who may read a file; the bucket does not. Encryption at rest and the bucket's policy are yours to set.
- **The marker.** When popdesk first uses a folder or a bucket that no attachment is stored in yet, it leaves a small file there: `.popdesk-storage` in the folder, `popdesk/.popdesk-storage` in the bucket. Do not remove it.
- **After you change the storage** (the kind, the folder, or the bucket), new files go to the new place and old files stay where they are. Keep the old folder mounted, and keep the S3 keys set, for as long as attachments are stored there.
- **Backups.** `popdesk backup` holds the files only when they are in `db`. For `disk` and `s3`, back up the folder or the bucket, each time after the database dump, marker included.

At every start popdesk checks the storage, and does not start when the check fails. The process ends, the log says why (setting keys, the storage kind, the place, and how many attachments are stored there; never a key's value), and Compose starts it again until the cause is gone:

| What popdesk finds | What it means |
|---|---|
| `POPDESK_STORAGE` is not `db`, `disk`, or `s3`; the folder is not an absolute path; `s3` without `S3_BUCKET`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, or without both `S3_REGION` and `S3_ENDPOINT` | The settings cannot be used. The log names the keys |
| Attachments are stored in `s3` and the S3 keys are not set, also when new files now go to `db` or `disk` | Old files could not be read. Set the keys again |
| Attachments are stored in a folder or a bucket that has no marker | The volume is not attached, another bucket is set, or the files are gone |
| The storage of new files cannot be written or reached: the folder belongs to another user or is read-only, the key pair is wrong, the bucket does not exist (popdesk does not make one), or the storage does not answer | For a folder the log names the user ID popdesk runs as. This also stops a start while S3 is down for a moment |

An installation whose settings and attachments are all `db` touches no folder and opens no connection to S3.

If the files of a place are lost for good and you want popdesk to start anyway, put the marker back by hand (an empty file with that name is enough). Downloads of the lost files then answer 500, and `popdesk attachments check` lists them.

While it runs, popdesk answers 500 `INTERNAL_ERROR` to an upload whose file cannot be stored and to a download whose file is missing or cannot be read, and logs the attachment's ID and the storage kind. Every request to S3 has a time limit (5 seconds to connect, 60 seconds for an answer), so a storage that does not answer holds an upload or a download for a minute at most. A cleanup job that cannot reach the storage stops its run and tries again later.

## Profile photos

A profile photo is a PNG, JPEG, or WebP file of at most 2 MB (2,097,152 bytes), judged by its content and not by its name. popdesk decodes it, scales it to fit 256 px, and keeps only that copy as a PNG in the database (table `account_photo`, a few tens of KB each); the uploaded file and its metadata are not kept. The limits are fixed, there is no setting for them: a PNG or JPEG may have up to 25 million pixels (width times height) and a WebP up to 12 million, read from the file's header before anything is decoded, and an account uploads at most 10 photos in 60 seconds. Decoding is what costs memory. PNG and JPEG are read at a reduced size and need little, but a WebP that is lossless or has transparency is decoded whole first, about 72 MB of heap for one at the limit. So at most 2 photos are decoded at once, which fits the image's 384 MB heap next to the app's other work; a third upload at that moment gets 503 `SERVER_BUSY` with `Retry-After`, and the screens try again. If you lower `-Xmx` below the default, leave room for about 150 MB of photo decoding. Photos are in the database dump, so `popdesk backup` covers them whatever `POPDESK_STORAGE` is.

## Commands

The `popdesk` command is in the app image. A command that changes data or takes it out (`admin reset-password`, `backup`, `restore`) is recorded in the audit log, with popdesk itself (account `0`) as the person. A command that fails changes nothing and ends with a non-zero exit code.

| Command | What it does |
|---|---|
| `popdesk admin reset-password <email>` | Asks twice for a new password for an admin account, without showing it, and sets it. The password must keep the password rules; the command does not ask again. The password is never printed or logged. It also unlocks the account and ends its sessions. It needs a terminal: run it as `docker compose exec app popdesk admin reset-password <email>` (without `-T`); without a terminal it fails. Use it when no admin can sign in |
| `popdesk backup <file>` | Writes a database dump in `pg_dump`'s custom format, while the app runs. The image's `pg_dump` has the same major version as the `db` image of its release; if the server has another major version, the command stops |
| `popdesk restore <file>` | Restores a dump into an empty database only, before the app starts serving. If the app is running on the database (it holds the single-instance lock) or the database has any table, it stops |
| `popdesk attachments check` | Looks at every stored attachment: whether its file is where it was stored and has the recorded size. It changes nothing, is not recorded in the audit log, and can run while the app runs. See [Check the attachment files](#check-the-attachment-files) |

### Back up

Write the dump inside the running app container, then copy it out:

```
docker compose exec app popdesk backup /tmp/popdesk.dump
docker compose cp app:/tmp/popdesk.dump ./popdesk-$(date +%F).dump
docker compose exec app rm /tmp/popdesk.dump
```

Keep the dump somewhere other than the Docker host. When attachments are stored in a folder or a bucket (`POPDESK_STORAGE` is `disk` or `s3`, or was), back up that folder or bucket after the database dump: the dump does not hold those files.

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

When attachments are stored in a folder or a bucket, restore that folder or bucket as well, from the backup taken after the dump, before you start the app. Then check that every file is there:

```
docker compose exec -T app popdesk attachments check
```

### Check the attachment files

`popdesk attachments check` prints one line for each file that is not as the database says, by attachment ID and never by file name, and then one line for each place that holds stored attachments:

```
wrong-size disk 1c0f6f0a-3a52-4b8e-9d0e-0d6a3f1f5b11
missing disk 7e2b9a64-55c1-4f0d-8a43-9b1d2c3e4f50
db attachment_content: checked 12, missing 0, wrong size 0
disk /data/attachments: checked 340, missing 1, wrong size 1
```

Without any stored attachment it prints `no stored attachments`.

| Exit code | Meaning |
|---|---|
| `0` | Every file is there and has the recorded size |
| `2` | Every file was looked at, and one or more is missing or has another size |
| `1` | The check did not finish: the database or a storage could not be reached, a setting is missing, or the command was called with another argument. Standard error has one line with the reason, starting with `popdesk: ` |

The command reads the same settings as the app, so run it in the app container. It asks S3 once for each attachment; ten thousand attachments in a bucket take a few minutes. A missing file means the folder or the bucket is older than the database dump, or is not the one this database was used with: the start check only sees that a place was used by popdesk, not by which installation.
