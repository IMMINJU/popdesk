# popdesk documentation

Public documentation. A documentation site will be built from this folder. Pages are added as features land.

| Section | For | Covers |
|---|---|---|
| [`operate/`](operate/) | People who install and run popdesk | [Installation](operate/installation.md), [configuration and TLS](operate/configuration.md), [email](operate/email.md), [backup and restore](operate/backup.md), [limits](operate/limits.md), [upgrade](operate/upgrade.md), [audit log](operate/audit-log.md), [personal data](operate/privacy.md) |
| [`use/`](use/) | Admins, agents, and customers | [Permissions](use/permissions.md). Guides for the agent console and the customer portal are not written yet |
| [`integrate/`](integrate/) | People who connect other systems | [Overview](integrate/README.md), [API conventions](integrate/conventions.md), API reference ([`openapi-portal.json`](integrate/openapi-portal.json), [`openapi-console.json`](integrate/openapi-console.json)), [team webhook](integrate/webhooks.md) |
| [`release-notes/`](release-notes/) | Everyone | What changed in each release |
| [`../CONTRIBUTING.md`](../CONTRIBUTING.md) | Contributors | Development setup, tests, how changes are made |

Builders' notes (decisions, roadmap, work in progress) are in [`../design/`](../design/) and are not part of this documentation.
