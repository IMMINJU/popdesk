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
