# popdesk

A self-hosted helpdesk that one support team runs for many customer companies.
Customers raise and follow requests in a customer portal; agents answer, track internal work, and hand off in an agent console.

[한국어](README.ko.md)

> **Status: 0.2.0.** An empty install, the first admin, an invitation, a request with attachments, an agent's answer, and the email and webhook that follow; managing people and customer companies, password reset, and an audit log with a hash chain. The image is not published yet; build it from the tag ([release notes](docs/release-notes/0.2.0.md)). Not ready for production. The plan and decisions live in [`design/`](design/).

## What it aims to be

- **Two containers.** One application image and one PostgreSQL. `docker compose up` and you have a helpdesk.
- **Small machine friendly.** Develops and runs on a 16 GB laptop.
- **Clear boundaries.** A customer never sees another customer company's requests. Internal notes, tasks, and assignees never reach customers.
- **Traceable.** Every request keeps its conversation, status history, and internal work log in one place. Admin actions are audited.

Planned features: requests with public and private visibility, public and internal comments, mentions, attachments and inline images, labels, due dates, internal tasks with assignees and due dates, linked duplicate and related requests across companies, status history, trash and auto-close, per-company counts, in-app inbox, email notifications, team webhooks (Slack, Teams, Mattermost), OIDC sign-in, and creating requests by email.

## Not goals

Chat, phone, or messenger channels; self sign-up; SLA reporting; real-time push; native apps; multiple teams in one install (for now). See [`design/roadmap.md`](design/roadmap.md).

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
