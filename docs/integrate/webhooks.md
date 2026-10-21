# Team webhook

popdesk can post a short message to one address when something happens to an issue, for example to a channel of your team chat. There is one webhook for the whole installation, set with `NOTIFY_WEBHOOK_URL`.

This page describes the code on the `main` branch, which will be released as 0.5.0. If you run 0.4.0, read the [docs at the tag `v0.4.0`](https://github.com/IMMINJU/popdesk/tree/v0.4.0/docs) instead; a page missing there did not exist yet.

## Turning it on

Set `NOTIFY_WEBHOOK_URL` to the address that receives the messages ([Environment variables](../operate/configuration.md#environment-variables)). Without it no webhook is sent, and nothing is queued for it.

- **The address is a secret.** Anyone who has it can post to your receiver, so keep it like a password. popdesk never writes it to its log or uses it as a metric label, and you can give it as a file with `NOTIFY_WEBHOOK_URL_FILE` ([Secrets](../operate/configuration.md#secrets)).
- **`http` works, with a warning.** popdesk accepts an `http` address and logs a warning at startup, without the address. Use `https` when the receiver is outside your network ([Webhook and audit copy](../operate/configuration.md#webhook-and-audit-copy)).
- **A sandbox turns it off.** With `POPDESK_SANDBOX=true` no webhook is sent although the address is set, and messages that were waiting in a copied database are dropped ([A sandbox](../operate/configuration.md#a-sandbox)).

## The request

Each message is one `POST` to the address:

```
POST <NOTIFY_WEBHOOK_URL>
Content-Type: application/json; charset=utf-8

{"text":"New issue ACME-12 from ACME: Printer does not start\nhttps://console.example.com/issues/ACME-12"}
```

- The body is a JSON object with one field, `text`. popdesk sends no signature and no `Authorization` header.
- `text` has two lines: what happened, the issue key, the company code, and the issue's title on the first, and the link to the issue in the agent console on the second.
- The text is in the installation's language (`POPDESK_LOCALE`), and it is made when the message is sent, from the issue as it is then.
- It never holds a customer's name or email address, or what an issue or a comment says. The title does go out, so choose a receiver that may see issue titles.

A body of this form, with a single `text` field, is what the incoming webhooks of chat tools such as Slack and Mattermost take. popdesk was made for that form, but its tests post to a local receiver only, so check that your tool shows the message before you rely on it.

## When a message is sent

| Kind | When | First line (English) |
|---|---|---|
| `ISSUE_CREATED` | A new issue: filed by a customer in the portal, by email, or by staff on behalf of a customer | `New issue ACME-12 from ACME: <title>` |
| `CUSTOMER_COMMENT` | A customer adds a comment. Comments by staff send no message | `Customer comment on ACME-12 from ACME: <title>` |
| `ISSUE_RESOLVED` | The issue becomes `RESOLVED` | `Issue ACME-12 from ACME resolved: <title>` |
| `ISSUE_CLOSED` | Staff or the reporter close the issue, or popdesk closes a resolved issue after 7 days | `Issue ACME-12 from ACME closed: <title>` |
| `ISSUE_REOPENED` | Staff or the reporter reopen the issue | `Issue ACME-12 from ACME reopened: <title>` |
| `ASSIGNEE_CHANGED` | Someone sets, changes, or clears the assignee. The message does not say who the assignee is | `Assignee of ACME-12 from ACME changed: <title>` |
| `SLA_RESPONSE_BREACHED` | The first response goal passes its due time; one message per goal ([SLA due times](../operate/configuration.md#sla-due-times)) | `SLA first response overdue ACME-12 from ACME: <title>` |
| `SLA_RESOLUTION_BREACHED` | The resolution goal passes its due time; one message per goal | `SLA resolution overdue ACME-12 from ACME: <title>` |

The kind is not in the body. When an admin sends a failed message again, the audit entry `DELIVERY_RESENT` records its kind ([audit-log.md](../operate/audit-log.md#sending-again-and-test-emails-in-the-log)).

Nothing is sent for:

- internal comments, tasks, labels, due dates, and links;
- clearing assignees in bulk when an admin takes an agent off a company or suspends an account;
- an issue in the trash. Deleting an issue also deletes its messages that are still waiting, so restoring it does not send them later.

## Delivery

- **Order and repeats.** A message that failed waits for its next try and does not hold back the ones after it, so messages can arrive out of order. popdesk marks a message as sent only after the receiver answered, so a receiver that handled a message but answered too late, or a restart in between, can get the same message twice.

Messages wait in a queue in the database and are sent in the background, oldest first, by a sender of their own, so a slow mail server does not hold them up. The rules are in [Sending email and webhook messages](../operate/configuration.md#sending-email-and-webhook-messages); in short:

- **Success** is any `2xx` answer. A redirect is not followed and counts as a failure.
- **Time limits**: 10 seconds to connect, 30 seconds for the whole request.
- **Retries**: a failed message is tried again after 1 second, then 2, 4, and so on, at most every 10 minutes. 24 hours after its first failure it is given up, and every admin gets an item in the console inbox. It is deleted 7 days later.
- **Errors**: a failed message shows the kind of its last error in the system status, never the error text: for example `CONNECTION_FAILED`, `TIMEOUT`, `TLS_FAILED`, `HTTP_` and the status code (`HTTP_404`), or `WEBHOOK_URL_INVALID` when the address cannot be used.
- **Watching it**: the console's system status shows whether the webhook is on, its last successful send, and the queued and failed messages, and an admin can send a failed message again there. When the webhook has only failed for over an hour, every admin gets `WEBHOOK_STALLED` in the inbox. The metrics `popdesk_outbox_queued`, `popdesk_outbox_failed`, and `popdesk_outbox_sent_total` have the label `channel="webhook"`.
