# popdesk

A self-hosted helpdesk that one support team runs for many customer companies.
Customers raise and follow requests (called issues in the docs and the API) in a customer portal; agents answer, track internal work, and hand off in an agent console.

[한국어](README.ko.md)

> **Status: 0.6.0.** Customer companies and their people, requests with attachments and inline images, comments with mentions, notification inboxes, email and a team webhook, and an audit log with a hash chain. New in 0.5: attachments in a folder or S3, labels, due dates, internal tasks, linked requests, trash and auto-close, per-company counts, SLA due times and alerts, branding, personal-data export and anonymization, OIDC sign-in, requests by email, and an English UI. The image is published for `amd64` and `arm64`. 0.6.0 makes searches of one or two characters use an index and fixes receiving email that the mail server will not hand over ([release notes](docs/release-notes/0.6.0.md), [0.5.0](docs/release-notes/0.5.0.md)). Not ready for production. The plan and decisions live in [`design/`](design/).

## What it aims to be

- **Two containers.** One application image and one PostgreSQL. `docker compose up` and you have a helpdesk.
- **Small machine friendly.** The app and its database use about 1 GB of memory. popdesk is built on a 16 GB laptop.
- **Clear boundaries.** A customer never sees another customer company's requests. Internal comments and assignees never reach customers.
- **Traceable.** Every request keeps its conversation, status history, and internal work log in one place. Admin actions are audited.

## Try it

You need Docker with Compose. The image is published for `amd64` and `arm64`.

```
mkdir -p popdesk/deploy
cd popdesk
curl -fL -o deploy/compose.yml https://github.com/IMMINJU/popdesk/releases/download/v0.6.0/compose.yml
cat > deploy/.env <<'EOF'
POPDESK_DB_PASSWORD=choose-a-database-password
POPDESK_ADMIN_EMAIL=admin@example.com
POPDESK_ADMIN_PASSWORD=choose-a-long-admin-password
EOF
docker compose -f deploy/compose.yml up -d
```

The admin password must be 10 to 72 characters. Then open `http://console.localhost:8080` and sign in as the admin; customers use `http://portal.localhost:8080`. These addresses only work in a browser on the same machine. Without mail settings popdesk sends no email, and the console shows each invitation link so you can pass it on.

To install it on a server, with your own host names, TLS, and email, follow [Installation](docs/operate/installation.md). Who may do what (admins, agents, customers, customer managers) is in [permissions.md](docs/use/permissions.md).

## Not goals

Chat, phone, or messenger channels; self sign-up; real-time push and browser notifications; native apps and offline use; branding per customer company; multiple teams in one install (for now). See [`design/roadmap.md`](design/roadmap.md).

## Repository layout

| Path | For | Language |
|---|---|---|
| `docs/` | People who install, use, integrate, or contribute. A documentation site will read this folder. | English |
| `design/` | People who build popdesk: decisions (ADR), roadmap, process, threat model, work in progress. Not published to the site. | Korean |
| `server/`, `web/`, `deploy/` | Code: the server, the two web apps, and the Compose files | |

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) and [AI_POLICY.md](AI_POLICY.md). Report security issues as described in [SECURITY.md](SECURITY.md), not in public issues.

## License

[Apache License 2.0](LICENSE)
