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
- Requests that change data must send the CSRF token: the server sets it in a readable cookie and the client returns it in a request header.

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
| 400 | `COMPANY_NOT_ACTIVE` | The customer company is not `ACTIVE`, so nothing can be filed in or added to it |
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
| 413 | `PAYLOAD_TOO_LARGE` | The request is over the app's upload limit. A proxy in front may answer 413 first with its own body |
| 429 | `RATE_LIMITED` | Over a rate limit (`COM-8`). `Retry-After` says when to try again |
| 500 | `INTERNAL_ERROR` | An unexpected server error |
| 501 | `NOT_IMPLEMENTED` | The endpoint is in the contract but not built yet. Only before a slice is finished; never in a release |
| 503 | `SERVER_BUSY` | 16 uploads, or 16 downloads, are already running. `Retry-After` says when to try again |
| 503 | `SETUP_REQUIRED` | The first admin could not be created, so the app takes no requests yet. The log says what to set |

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
| Upload an attachment | 30 |
| List with a search term | 60 |
| Customer manager sends an invitation | 20 |
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
