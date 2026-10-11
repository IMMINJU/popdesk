# API conventions

Rules that every popdesk API endpoint follows. This is a canonical document: code, tests, and the generated OpenAPI files follow it. Rule IDs in parentheses (for example `COM-3`) are used in test names and in other documents.

popdesk is not released yet. Each endpoint follows these rules from the slice that ships it.

## API paths and contract files

The API is split by app (ADR D-43):

| Path | For | Served on |
|---|---|---|
| `/api/portal/**` | The customer portal | Portal host only |
| `/api/console/**` | The agent console (agents and admins) | Console host only |
| `/api/public/**` | Before sign-in: branding, accepting an invitation, sign-in | Both hosts |

A request for a path that its host does not serve gets 404. Customer and staff endpoints use separate request and response types; customer types have no internal fields or filters (D-41).

The contract is generated from code by springdoc and committed:

- `docs/integrate/openapi-portal.json`: `/api/portal/**` and `/api/public/**`
- `docs/integrate/openapi-console.json`: `/api/console/**` and `/api/public/**`

CI fails when the committed files differ from the code. The TypeScript clients in `web/packages/api` are generated from these files.

## Sessions and CSRF

- Sign-in creates a server session held in an `HttpOnly` cookie. Cookies are host-only, so a portal session does not work on the console host and the reverse (D-9).
- Requests that change data must send the CSRF token: the server sets it in a readable cookie and the client returns it in a request header. Before sign-in, `GET /api/public/csrf` sets the cookie. The cookie is `XSRF-TOKEN` (readable by scripts) and the header is `X-XSRF-TOKEN`; the client sends the cookie value as it is.

## Invitation links

An invitation link has the form `{scheme}://{host}[:{port}]/invite#token={token}` (INVITE-2). The host is the portal host for customers and the console host for agents and admins, and the port is left out when it is the default for the scheme (S0-AC-20). The token is only in the fragment after `#`, so browsers and proxies never send it in a request line or a `Referer`. The page reads it and sends it in the body of `POST /api/public/invitations/accept`.

## Order of checks and status codes (COM-1, COM-2)

Every request is checked in this order:

1. Signed in? If not, 401.
2. Is the target within what the caller may see? If not, 404.
3. May the caller do this to the target? If not, 403.
4. Is the input valid? If not, 400.
5. Does the current state allow it? If not, 400 or 409.

- A target the caller may not see (outside their scope, does not exist, or in the trash) is 404. The response does not reveal whether it exists (`COM-1`).
- A target the caller can see but may not act on is 403 (`COM-2`).
- An action reserved for other roles, whatever the target (for example a customer calling a task endpoint), is 403 and is checked before the target.

Rate limits (`COM-8`) are counted between steps 1 and 2.

## Error response (COM-3)

Every error response has the same body:

```json
{
  "code": "ISSUE_CLOSED",
  "message": "This issue is closed."
}
```

A 400 for invalid input may also list every invalid field (D-44):

```json
{
  "code": "INVALID_INPUT",
  "message": "Some fields are invalid.",
  "fields": [
    { "field": "title", "code": "TOO_LONG" },
    { "field": "priority", "code": "UNKNOWN_VALUE" }
  ]
}
```

`fields` is present only when the error is about specific fields. Clients match `fields[].field` to their inputs and choose the text by `fields[].code`.

- `code` is a stable key. Clients choose the text they show by `code` and use `message` only for a `code` they do not know.
- `message` is a default text in the installation language (`POPDESK_LOCALE`).
- The body never contains exception names, stack traces, or SQL.

### Error codes

Each endpoint lists the codes it can return in the OpenAPI files. A new code is added to this table first.

| Status | `code` | When |
|---|---|---|
| 400 | `INVALID_INPUT` | One or more request fields or query parameters are invalid. `fields` lists all of them |
| 400 | `INVITATION_INVALID` | The invitation token is unknown, expired, already used, or replaced by a newer one. One body for all of these |
| 400 | `PASSWORD_RESET_INVALID` | The password reset token is unknown, over 1 hour old, already used, or replaced by a newer one, its account is no longer `ACTIVE`, or it is sent to the other app's host. One body for all of these |
| 400 | `CURRENT_PASSWORD_WRONG` | The current password sent with a password change is wrong |
| 400 | `UNSUPPORTED_IMAGE` | The profile photo is not a PNG, JPEG, or WebP file, judged by its content, or it cannot be decoded |
| 400 | `IMAGE_TOO_LARGE` | The profile photo has too many pixels: width times height over 25 million for PNG and JPEG, 12 million for WebP |
| 400 | `OWN_ACCOUNT` | An admin tries to suspend their own account or change their own role, or a customer manager tries to remove themselves from the company |
| 400 | `CUSTOMER_ROLE_FIXED` | A role change to `CUSTOMER`, or of a customer's account. Only `AGENT` and `ADMIN` change into each other |
| 400 | `LAST_ADMIN` | The suspension or role change would leave no `ACTIVE` admin |
| 400 | `ACCOUNT_NOT_ACTIVE` | The account is not `ACTIVE`, so its password cannot be reset by an admin; or it never accepted its invitation, so it cannot be suspended (cancel the invitation instead) |
| 400 | `INVITATION_NOT_ACCEPTED` | The account never accepted its invitation, so its suspension cannot be lifted. Send the invitation again instead |
| 400 | `INVITATION_ALREADY_ACCEPTED` | The account accepted its invitation before, so the invitation cannot be sent again or cancelled |
| 400 | `COMPANY_ARCHIVED` | The customer company is `ARCHIVED`: its status, name, and legal name no longer change |
| 400 | `COMPANY_NOT_ACTIVE` | The customer company is not `ACTIVE`, so nothing can be filed in or added to it |
| 400 | `MEMBER_IS_MANAGER` | A customer manager tries to remove another manager of the company. An admin does that |
| 400 | `INVITATION_NOT_RESENDABLE` | A customer manager tries to send again an invitation that cannot be sent again from the portal. One code whatever the reason |
| 400 | `NOT_A_CUSTOMER` | The account of a membership request is an agent or an admin, so the request cannot be approved. It can be rejected |
| 400 | `ISSUE_CLOSED` | The issue is `CLOSED`: no comments, attachments, or edits |
| 400 | `FILE_EMPTY` | The uploaded file has 0 bytes |
| 400 | `FILE_TOO_LARGE` | The uploaded file is over 10 MB |
| 400 | `ISSUE_ATTACHMENTS_TOO_LARGE` | The public attachments of the issue (on the issue and its public comments) would pass 50 MB |
| 400 | `ATTACHMENT_QUOTA_EXCEEDED` | All attachments of the team would pass `POPDESK_ATTACHMENT_QUOTA` |
| 401 | `UNAUTHENTICATED` | No session, or the session ended |
| 401 | `SIGN_IN_FAILED` | Sign-in failed. The same body whatever the reason: unknown email, wrong password, invited, suspended, or locked account, or an account of the other app |
| 403 | `FORBIDDEN` | The caller may not do this: the action is for other roles, or the caller can see the target but may not act on it |
| 403 | `CSRF_TOKEN_INVALID` | A request that changes data has no CSRF token or a wrong one |
| 404 | `NOT_FOUND` | The target does not exist or the caller may not see it (`COM-1`). Also a path the host does not serve |
| 409 | `COMPANY_CODE_TAKEN` | Another customer company has this code |
| 409 | `EMAIL_TAKEN` | An account has this email |
| 409 | `ALREADY_LISTED` | The email a customer manager invites is already a row of that company's member list |
| 409 | `TOO_MANY_PENDING` | The company already has 100 rows waiting in its member list (`INVITED`), so its manager cannot invite more |
| 409 | `REQUEST_CLOSED` | The membership request was closed the other way (approved when rejecting, rejected when approving), or its manager took it back |
| 409 | `AUDIT_VERIFICATION_RUNNING` | A check of the audit log's hash chain is already running. One runs at a time |
| 413 | `PAYLOAD_TOO_LARGE` | The request is over the app's upload limit, a profile photo is over 2 MB (2,097,152 bytes), or the JSON of a new issue (the body, or the `issue` part of a multipart request) is over 256 KB (262,144 bytes). The headers of one multipart part, which carry the file name, are limited to 4 KB: a file name far over 255 characters gets this 413, not the 400 `TOO_LONG`. A proxy in front may answer 413 first with its own body |
| 429 | `RATE_LIMITED` | Over a rate limit (`COM-8`). `Retry-After` says when to try again |
| 500 | `INTERNAL_ERROR` | An unexpected server error |
| 501 | `NOT_IMPLEMENTED` | The endpoint is in the contract but not built yet. Only before a slice is finished; never in a release |
| 503 | `SERVER_BUSY` | 16 uploads, or 16 downloads, are already running, 2 profile photos are already being decoded, or 2 audit log exports are already running. `Retry-After` says when to try again |
| 503 | `SETUP_REQUIRED` | The first admin could not be created, or `POPDESK_LOCALE` is not a value popdesk takes, so the app takes no requests yet. The log says what to set |

A request to a host that is neither the portal host nor the console host gets 404 with a plain text note, not this body.

Codes in `fields[].code`:

| `code` | When |
|---|---|
| `REQUIRED` | The value is missing or empty |
| `BLANK` | The value has only whitespace |
| `TOO_SHORT` | Under the minimum length |
| `TOO_LONG` | Over the maximum length |
| `INVALID_FORMAT` | Does not match the expected form, for example an email address or a company code |
| `UNKNOWN_VALUE` | Not one of the allowed values, or names something that does not exist or the caller cannot choose |
| `OUT_OF_RANGE` | A number outside its range, for example `page` below 1 or `size` above 100 |
| `FROM_AFTER_TO` | The start of a period is after its end: `from` after `to` in the audit log filters. Reported on `from` |
| `NOT_ALLOWED` | The field must be left out here, for example companies when inviting staff |
| `SAME_AS_EMAIL` | The password is the same as the account's email |
| `UNKNOWN_PARAMETER` | The query parameter is not accepted on this endpoint, for example a staff-only filter on a customer list |

## Attachment paths

Attachments are always addressed under their issue:

```
/api/portal/issues/{key}/attachments/{id}
/api/console/issues/{key}/attachments/{id}
```

- The server finds the attachment with the same query that checks the issue: the caller must be able to see the issue, the attachment must belong to that issue, and for customers it must not belong to an internal comment.
- Every attachment endpoint (download, delete, `HEAD`, range requests) answers 404 with the same status, headers, and body in all of these cases: the ID does not exist, the caller cannot see the issue, the attachment belongs to another issue, a customer asks for an attachment on an internal comment, or the attachment is still being written or already deleted (`pending`, `purging`, `purged`). The response does not tell these cases apart (`COM-1`).
- An `{id}` that is not a UUID in its 36-character form (`8-4-4-4-12` hexadecimal digits, either letter case) is an ID that does not exist: the same 404.
- Attachment IDs are hard to guess (`ATT-7`), but that is not a permission check.
- Uploads and downloads are each limited to 16 at a time. Over the limit, the response is 503 with a `Retry-After` header.

## Retrying on lock conflicts

Each database connection has `lock_timeout` set to 5 seconds. When a transaction fails with a deadlock (`40P01`) or a lock timeout (`55P03`), the server retries the whole transaction once. If the retry also fails, the request fails like any other server error; clients see a 5xx and may try again later.

## Pagination (COM-4)

List endpoints take:

| Parameter | Meaning | Default |
|---|---|---|
| `page` | Page number, starting at 1 | `1` |
| `size` | Items per page, 1 to 100 | `20` |

The response is:

```json
{
  "items": [],
  "page": 1,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0
}
```

A `page` past the last page returns an empty `items`. A `page` below 1 or a `size` outside 1 to 100 is 400.

## Search (COM-5)

- The search parameter `q` is 1 to 100 characters.
- Matching ignores case and finds the text anywhere in the field, not by word.
- `%` and `_` are ordinary characters, not wildcards.
- Search uses a trigram index (D-13).

## Text input

- Lengths are counted in Unicode code points.
- All string input is normalized to NFC before it is checked and stored.
- One-line values (titles, names) have leading and trailing whitespace removed before they are checked and stored. Multi-line text (issue content, comments) is not trimmed.
- Text with a NUL character (U+0000) or a surrogate without its pair (a code unit from U+D800 to U+DFFF that is not half of a high-low pair) is refused: 400 `INVALID_INPUT` with that field `INVALID_FORMAT`. Neither can be stored or shown. A surrogate pair, which is one character outside the basic plane such as an emoji, is taken as it is.

## Markdown content (COM-6)

Issue content and comments are stored as the Markdown the author wrote. Clients render it with an allow list: no scripts, no event attributes, no `javascript:` links.

## Deletion that leaves a trace (COM-7)

Some deletions keep the row and leave a trace: the content fields are cleared, and the row records `deleted`, the time of deletion, and who deleted it. Lists show the item in its place as "Deleted".

## Rate limits (COM-8)

Limits are per account over a 60-second window:

| Action | Limit |
|---|---|
| Create an issue | 10 |
| Add a comment | 30 |
| Upload an attachment | 30 (each file of a new issue counts as one; a new issue whose files do not all fit is refused whole) |
| Upload a profile photo | 10 |
| List with a search term | 60 |
| Customer manager sends an invitation, or sends one again | 20 (both count together) |
| Sign-in attempts | 20 per IP address |
| Password reset requests | 20 per IP address; at most one reset email per account every 15 minutes |

- Over the limit, the response is 429 with a `Retry-After` header and the error body.
- Counts are kept in the app's memory; popdesk runs one app instance (D-23).
- The count is taken right after the sign-in check and before the target is looked up, so a 429 never tells whether a target exists.
- Issues and comments created from incoming email count against the sender's account in the same way. Email over the limit is not turned into issues or comments; it is moved to the quarantine folder.

## Time and time zone (TIME-1)

- Timestamps are stored in UTC and returned as ISO-8601 with an offset, in UTC: `2026-10-08T03:15:00Z` (D-10).
- Timestamps sent to the API must include an offset. A timestamp without one is 400.
- Date-only values (for example due dates) are `YYYY-MM-DD`. "Today" for these values is judged in the installation time zone (`POPDESK_TIMEZONE`, default `UTC`).

- Periods (24 hours, 7, 14, or 30 days, retention periods) are measured as a difference of UTC times. Daylight saving time and changes to `POPDESK_TIMEZONE` do not change their length. The installation time zone is used only to judge dates: due dates and SLA business hours and holidays.

## Missing values and `null`

Decided in ADR D-44.

- In responses, a field without a value is present with `null`. Fields are never left out.
- A list field without items is `[]`, never `null`.
- In update requests (`PATCH`):
  - A field left out of the request keeps its current value.
  - A field sent as `null` is cleared. This works only for fields that may be empty; sending `null` for a required field is 400.

## Audit log hash chain

Every audit entry has a place in one chain and a hash over its values and the entry before it (AUDIT-6). An export is enough to compute every hash again; `docs/integrate/audit-verify.py` does it with the Python standard library.

- `seq` is the entry's place in the chain, from 1, without gaps. The chain's order is `seq`, not the time: an entry's time is when the action was done, and it joins the chain when its change is committed, so two entries can have times in the other order.
- `hash = SHA-256(prevHash ‖ canonical JSON)`, written as 64 lowercase hex characters. `prevHash` goes in as its 64 characters (64 ASCII bytes), not as 32 bytes. With a shell: `printf '%s%s' "$prevHash" "$json" | sha256sum`.
- `prevHash` is the `hash` of the entry with the `seq` before. The first entry's `prevHash` is 64 zeros.
- The canonical JSON is the UTF-8 text of this object by RFC 8785 (JCS): keys sorted by UTF-16 code units, no whitespace, strings escaped as `JSON.stringify` does.

  ```json
  {"action":"…","actor":{"accountId":1,"email":"…","name":"…"},"changes":{…},"occurredAt":"2026-10-08T03:15:00.123456Z","target":{…}}
  ```

  - All five keys are always there. `target` and `changes` are `null` when the entry has none; `email` and `name` are `null` for popdesk itself (`accountId` 0).
  - `occurredAt` is UTC with exactly six fraction digits and `Z`.
  - `target` and `changes` are the stored values as they are. A time inside them is text in the same form as `occurredAt`.
  - Values hold no fractions: every number is a whole number of at most 64 bits. So no number needs the number rules of RFC 8785.
- An entry that changes a value keeps it under the field's name as `{"from": before, "to": after}`. An entry that sets values for the first time (creating a company, an invitation) keeps the value itself. New kinds of entry follow this.
- A name once used for `action`, or for a key of an entry, is never renamed: it is part of the hash.
- After the retention period removed old entries, the chain starts at the first entry that is left. Its `prevHash` is the `hash` of the last entry removed, which popdesk keeps as the chain's starting point; the integrity check starts there.

The integrity check (`verifyAuditLog`) computes the same from the database and also compares the last entry with the recorded end of the chain. It finds a changed entry, a removed entry, and removed last entries. It cannot find a chain that someone with write access to the database computed again from start to end; keep the last `hash` of an export somewhere else, or send the entries to another system as they are written ([outside copy](#audit-log-outside-copy)).

## Audit log export

`exportAuditLog` gives the entries the filters find, in chain order (`seq`, lowest first), up to the end of the chain as it was when the export started. The hashes are the stored ones; the export never computes them again.

JSON Lines (`application/x-ndjson`): one JSON object per line, lines end with a line feed.

| Field | Value |
|---|---|
| `id` | The entry's ID |
| `seq` | Its place in the chain |
| `prevHash`, `hash` | As in the hash chain |
| `action` | The kind of action |
| `actor` | `{"accountId", "name", "email"}` as stored when the entry was written |
| `target`, `changes` | The stored JSON, or `null` |
| `occurredAt` | UTC with six fraction digits |

Use JSON Lines to check the chain. Only a file made without a filter has every entry; a filtered file has gaps in `seq`.

CSV (`text/csv; charset=UTF-8`) is for reading in a spreadsheet. The file starts with a byte order mark; rows end with CRLF; the first row is the header.

- Columns: `id`, `seq`, `prevHash`, `hash`, `action`, `actorAccountId`, `actorName`, `actorEmail`, `target`, `changes`, `occurredAt`.
- `target` and `changes` are JSON text in one column each (`null` when the entry has none). `actorName` and `actorEmail` are empty for popdesk itself.
- Every value is in double quotes, and a quote inside is doubled.
- A value that starts with `=`, `+`, `-`, `@`, a tab, or a line break gets an apostrophe (`'`) in front, so a spreadsheet does not run it as a formula. The apostrophe is not part of the stored value; do not compute hashes from a CSV file.

## Audit log outside copy

With `AUDIT_FORWARD_URL` set, popdesk sends every audit entry to that address as it is written (AUDIT-9), so a system outside popdesk holds the entries and their hashes. Operating it is in [docs/operate/audit-log.md](../operate/audit-log.md#a-copy-outside-popdesk).

One request per entry:

```
POST <AUDIT_FORWARD_URL>
Content-Type: application/json
Authorization: Bearer <AUDIT_FORWARD_TOKEN>

{"id":412,"seq":398,"prevHash":"…","hash":"…","action":"ACCOUNT_SUSPENDED","actor":{"name":"…","email":"…","accountId":3},"target":{…},"changes":null,"occurredAt":"2026-10-08T03:15:00.123456Z"}
```

- The body is the entry with the fields of a JSON Lines export line, described above: `id`, `seq`, `prevHash`, `hash`, `action`, `actor`, `target`, `changes`, `occurredAt`. The values and hashes are the stored ones, read when the request is sent. A receiver can compute `hash` from the body alone, by the rules of the hash chain. Ignore fields you do not know; later versions may add some.
- The `Authorization` header is there only when `AUDIT_FORWARD_TOKEN` is set.
- Answer with any `2xx` once the entry is stored. Any other answer, a redirect (it is not followed), no answer within 30 seconds, or a failed connection counts as a failure.
- **Delivery is at least once.** A failed entry is sent again after 1 second, then 2, 4, and so on, at most every 10 minutes, for 24 hours after its first failure. An entry can arrive twice, for example when the answer was lost or popdesk restarted between sending and noting it. Keep one entry per `id`: an entry never changes, so a second arrival with the same `id` holds the same body.
- **Order is not guaranteed.** Entries are sent one at a time, oldest first, but an entry that failed waits for its next try while later ones go out. Put the entries in order by `seq`, or follow `prevHash`: the entry after one with hash `h` is the one whose `prevHash` is `h`. A gap in `seq` means an entry has not arrived yet, or was given up.
- **Only entries written while the address is set are sent.** Entries from before are not sent later; get them with an export.
- An entry still failing 24 hours after its first failure is given up and not sent again; the console's system status lists it with its `auditEntryId`. This version has no API to send it again. Fill the gap from an export: its lines have the same fields.
- The outside copy is off while `POPDESK_SANDBOX=true`.
