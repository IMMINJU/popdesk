# Integrating with popdesk

What other systems can use: the API, the team webhook, and the audit log.

This page describes the code on the `main` branch. The latest release is 0.5.1. If you run an older version, read the docs at its tag, such as [`v0.4.0`](https://github.com/IMMINJU/popdesk/tree/v0.4.0/docs); a page missing there did not exist yet.

## The API

The API is split by app. Each path is served on one host only, so a call to the other host gets 404 ([API paths and contract files](conventions.md#api-paths-and-contract-files)):

| Path | For | Served on |
|---|---|---|
| `/api/portal/**` | The customer portal | The portal host |
| `/api/console/**` | The agent console, for agents and admins | The console host |
| `/api/public/**` | Before sign-in: branding, the CSRF token, invitations, password reset, sign-in, and sign-out | Both hosts |

| File | What it is |
|---|---|
| [`conventions.md`](conventions.md) | The rules every endpoint follows: sessions and CSRF, status codes and the error body, pagination, search, rate limits, time, and the export and outside copy formats |
| [`openapi-portal.json`](openapi-portal.json) | The OpenAPI contract of `/api/portal/**` and `/api/public/**` |
| [`openapi-console.json`](openapi-console.json) | The OpenAPI contract of `/api/console/**` and `/api/public/**` |

The two contract files are generated from the code, so they always match the version they come with.

There are no API tokens or service accounts. A script signs in like a person, with an account's email and password, and then sends the session cookie and the CSRF token as the web apps do ([conventions.md](conventions.md)).

## The team webhook

One address that gets a short JSON message when an issue is created, commented on by a customer, resolved, closed, reopened, or reassigned, or misses an SLA goal. See [webhooks.md](webhooks.md).

## The audit log

- **Export.** Admins export the audit log from the console as JSON Lines or CSV ([format](conventions.md#audit-log-export)).
- **Check an export yourself.** [`audit-verify.py`](audit-verify.py) checks the hash chain of a JSON Lines export made without a filter. It needs Python 3 and nothing else: `python3 audit-verify.py export.jsonl`. Exit code 0 means the chain holds.
- **An outside copy.** With `AUDIT_FORWARD_URL` set, popdesk posts every new audit entry to that address ([format](conventions.md#audit-log-outside-copy)).

What the hash chain can and cannot show, and how to keep a copy where the database administrator cannot change it, is in [audit-log.md](../operate/audit-log.md).
