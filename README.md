# popdesk

A self-hosted helpdesk that one support team runs for many customer companies.
Customers raise and follow requests (called issues in the docs and the API) in a customer portal; agents answer, track internal work, and hand off in an agent console.

[한국어](README.ko.md)

> **Status: 0.4.0.** An empty install, the first admin, an invitation, a request with attachments, an agent's answer, and the email and webhook that follow; managing people and customer companies, password reset, and an audit log with a hash chain; customer managers, and for requests the filters, search, editing, assignee, status changes, closing and reopening, history, and filing on behalf of a customer; editing and deleting comments, mentions, a notification inbox in both apps, more emails, and tools for the operator to see and repair sending. The image is not published yet; build it from the tag ([release notes](docs/release-notes/0.4.0.md)). Not ready for production. The plan and decisions live in [`design/`](design/).
>
> `main` is ahead of 0.4.0 and works toward 0.5.0, which is not released yet. To try popdesk, check out the tag `v0.4.0`.

## What it aims to be

- **Two containers.** One application image and one PostgreSQL. `docker compose up` and you have a helpdesk.
- **Small machine friendly.** The app and its database use about 1 GB of memory. popdesk is built on a 16 GB laptop.
- **Clear boundaries.** A customer never sees another customer company's requests. Internal comments and assignees never reach customers.
- **Traceable.** Every request keeps its conversation, status history, and internal work log in one place. Admin actions are audited.

On `main` and coming in 0.5.0: inline images and S3 storage for attachments, labels, due dates, internal tasks with assignees and due dates, linked duplicate and related requests across companies, trash and auto-close, per-company counts, SLA due times and alerts, branding, personal-data export and anonymization, OIDC sign-in, creating requests by email, and an English UI.

## Try it

No image is published yet, so build 0.4.0 from its tag. You need Git and Docker with Compose.

```
git clone https://github.com/IMMINJU/popdesk.git
cd popdesk
git checkout v0.4.0
docker build -t ghcr.io/imminju/popdesk:0.4.0 .
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
