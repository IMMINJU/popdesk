# Limits

The size, length, rate, and concurrency limits of popdesk in one table, with a link to where each is explained. Unless the table says so, a limit is fixed and has no setting.

This page describes the code on the `main` branch. The latest release is 0.5.0. If you run an older version, read the docs at its tag, such as [`v0.4.0`](https://github.com/IMMINJU/popdesk/tree/v0.4.0/docs); a page missing there did not exist yet.

## Files and request size

| What | Limit | More |
|---|---|---|
| One attachment | 10 MB | [Attachments](configuration.md#attachments) |
| Public attachments of one issue (on the issue and its public comments) | 50 MB | [Attachments](configuration.md#attachments) |
| Files on a new issue | 20 | [Attachments](configuration.md#attachments) |
| All attachments together | `POPDESK_ATTACHMENT_QUOTA`, default `10GB`; warning at 80% | [Environment variables](configuration.md#environment-variables) |
| Request body the app takes | 55 MB, with files of up to 11 MB | [Attachments](configuration.md#attachments) |
| Request body at your proxy | Set it to at least 55 MB | [Request body size at the proxy](configuration.md#request-body-size-at-the-proxy) |
| JSON of a new issue | 256 KB | [Error codes](../integrate/conventions.md#error-codes) (`PAYLOAD_TOO_LARGE`) |
| Temporary images (uploaded for content, not saved yet) | At most 20 images and 50 MB in all per account; deleted 24 hours after upload if not saved | [Attachments](configuration.md#attachments) |
| Profile photo | 2 MB; PNG, JPEG, or WebP; up to 25 million pixels (WebP 12 million) | [Profile photos](configuration.md#profile-photos) |
| Branding logo | 512 KB; same formats and pixel limits | [Profile photos](configuration.md#profile-photos) |

## Text

Lengths are counted in Unicode characters (code points), after the text is normalized and one-line values are trimmed ([Text input](../integrate/conventions.md#text-input)).

| What | Limit |
|---|---|
| Issue title | 200 |
| Issue content | 10,000 |
| Comment | 4,000 |
| Task title | 100 |
| Task note | 4,000 |
| Name of a person | 50 |
| Company name and legal name | 100 |
| Label name | 30 |
| Branding display name | 50 |
| Attachment file name | 255 |
| Password | 10 to 72 |
| Search text (`q`) | 1 to 100 ([Search](../integrate/conventions.md#search-com-5)) |

## Counts

| What | Limit | More |
|---|---|---|
| Labels in a company | 200 | [Customer companies](../use/permissions.md#customer-companies) |
| Labels on an issue | 20 | [Issues](../use/permissions.md#issues) |
| Links of an issue | 50 | [Linked issues](../use/permissions.md#linked-issues) |
| Tasks of an issue | 30 not deleted, 100 with the deleted ones | [Tasks and task notes](../use/permissions.md#tasks-and-task-notes) |
| Notes of a task | 100 not deleted | [Tasks and task notes](../use/permissions.md#tasks-and-task-notes) |
| Accounts one comment mentions | 10 | [Comments and mentions](../use/permissions.md#comments-and-mentions) |
| Open invitations and membership requests of one company, sent by its manager | 100 | [Customer manager](../use/permissions.md#customer-manager) |

## Rates

| What | Limit | More |
|---|---|---|
| Actions per account (new issues, comments, uploads, searches, and more) | Per 60 seconds, by action; over the limit 429 | [Rate limits (COM-8)](../integrate/conventions.md#rate-limits-com-8) |
| Sign-in attempts, OIDC sign-in starts, password reset requests | 20 per IP address in 60 seconds, each | [Rate limits (COM-8)](../integrate/conventions.md#rate-limits-com-8) |
| Password reset email | One per account every 15 minutes | [Rate limits (COM-8)](../integrate/conventions.md#rate-limits-com-8) |
| Email to one address | 50 an hour; the 51st fails with `RATE_LIMITED` | [Sending email and webhook messages](configuration.md#sending-email-and-webhook-messages) |
| Test email | 5 a minute per admin | [Sending email and webhook messages](configuration.md#sending-email-and-webhook-messages) |

The counts are kept in memory, so they start again when popdesk restarts.

## Receiving email

| What | Limit | More |
|---|---|---|
| Emails read in one round | 100, once a minute | [Receiving email](configuration.md#receiving-email) |
| One email | 30 MB, 200 parts, parts nested 10 deep; over that it is quarantined | [Receiving email](configuration.md#receiving-email) |
| Tries before an email is quarantined | 5 | [Receiving email](configuration.md#receiving-email) |
| Issues and comments from email | Count toward the sender's rate limits; over them, the email is quarantined | [Rate limits (COM-8)](../integrate/conventions.md#rate-limits-com-8) |

## At the same time

| What | Limit | Over the limit | More |
|---|---|---|---|
| Uploads | 16 | 503 `SERVER_BUSY` with `Retry-After` | [Attachments](configuration.md#attachments) |
| Downloads of attachments | 16 | 503 `SERVER_BUSY` with `Retry-After` | [Attachments](configuration.md#attachments) |
| Images decoded (profile photos and logos together) | 2 | 503 `SERVER_BUSY` with `Retry-After` | [Profile photos](configuration.md#profile-photos) |
| Password hashes computed | 4 | The rest wait their turn | [Memory and JVM options](configuration.md#memory-and-jvm-options) |
| Company exports in the installation | 1 | 409 `EXPORT_RUNNING` | [Company exports](configuration.md#company-exports) |
| Account exports | 2 | 503 `SERVER_BUSY` with `Retry-After` | [Account export and anonymization](../integrate/conventions.md#account-export-and-anonymization-pii-3-pii-4) |
| Audit log integrity checks | 1 | 409 `AUDIT_VERIFICATION_RUNNING` | [How long the check and an export take](audit-log.md#how-long-the-check-and-an-export-take) |
| Audit log exports | 2 | 503 `SERVER_BUSY` with `Retry-After` | [How long the check and an export take](audit-log.md#how-long-the-check-and-an-export-take) |
| Anonymizations | 1 | 409 `ANONYMIZATION_RUNNING` | [After an anonymization](audit-log.md#after-an-anonymization) |
