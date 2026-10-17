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

## Branding

An installation has one branding (BRAND-1): a display name, an accent color, and a logo for the light and one for the dark theme. Until an admin changes it, it is the default: `popdesk`, no accent color (the apps' own colors), no logos.

- `GET /api/public/branding` answers on both hosts without a session, before sign-in too (BRAND-5). It has the display name, the values made from the accent color (`colors`, null without one), and the two logo addresses, and nothing else: no version, no one who changed it, no contrast, no logo size. With a logo for one theme only, both addresses point at it; with none, both are null. Like any other API response it is `no-store`; the apps read it once when they start.
- A logo address is `/api/public/branding/logos/{theme}?v={fingerprint}`, `theme` being `light` or `dark`. The answer is always a PNG that popdesk made, with `X-Content-Type-Options: nosniff` and a strong `ETag` (the fingerprint); `If-None-Match` with it gets 304. When `v` is the logo's fingerprint the answer is `Cache-Control: public, max-age=31536000, immutable`, otherwise `no-cache`: a new logo has a new fingerprint, so the address in the branding changes with it and a browser never keeps an old logo. A theme without a logo, or a `theme` other than the two, is 404; the logo of the other theme is never given in its place. `HEAD` has the same headers.
- Admins change the branding under `/api/console/branding/**`; agents get 403 there, checked before anything in the request (S9-AC-35). `PUT /api/console/branding` saves the display name and the accent color together with the `version` read (409 `VERSION_CONFLICT` otherwise). `GET /api/console/branding/accent?color=%23RRGGBB` gives the values a color would make and their contrast without saving it.
- popdesk makes the eight values (`accent`, `accentHover`, `onAccent`, `accentSubtle` of each theme) from the one color: the light `accent` is the color as given, the dark one has its OKLCH lightness moved (ADR D-50, D-61). A color is taken when the `accent` of each theme is 4.5:1 or more on every background it is text on; otherwise 400 with `accent` `LOW_CONTRAST`. The accent color changes only these four values of each theme, never focus rings, status colors, or error colors.
- A logo is uploaded and removed on its own, `PUT` and `DELETE /api/console/branding/logos/{theme}`, and is saved at once. It is `PUT`, where a profile photo is `POST`, because each theme has one logo that the request puts in place. A logo change does not change the branding's `version`, so an admin editing the name meanwhile gets no 409. The file is a PNG, JPEG, or WebP of up to 512 KB (524,288 bytes), judged by its content; an SVG is not taken. popdesk turns it by its EXIF orientation, scales it to fit 512 by 128 px, and keeps only that copy as a PNG without metadata. The same file always makes the same PNG, so uploading it again changes nothing, its address included. An upload is checked in this order: role (403); a request without the `file` part is 400 `file` `REQUIRED` before anything else in it is looked at; then the rate limit (429, refused uploads count too), `theme` (404), the size of the file (413), a place among the images being decoded (503), and the file's first bytes and pixel count (400). A refused upload leaves the logo there was.

## Order of checks and status codes (COM-1, COM-2)

Every request is checked in this order:

1. Signed in? If not, 401.
2. Is the target within what the caller may see? If not, 404.
3. May the caller do this to the target? If not, 403.
4. Is the input valid? If not, 400.
5. Does the current state allow it? If not, 400 or 409.

- A target the caller may not see (outside their scope, does not exist, or in the trash) is 404. The response does not reveal whether it exists (`COM-1`).
- An issue in the trash, or purged, is 404 on every issue path of both apps, to admins too: the issue and everything under it (comments, attachments and their files, history, tasks and notes, links, labels, due date, assignee, status, mention candidates). Deleting it again is 404 too, and so is a filing sent again with the `Idempotency-Key` of the request that filed it. Only the trash paths (`/api/console/trash/{key}…`, admins only) reach it.
- A target the caller can see but may not act on is 403 (`COM-2`).
- An action reserved for other roles, whatever the target (for example an agent calling an endpoint for admins only), is 403 and is checked before the target.
- A customer never reaches a console endpoint, a task endpoint for example: the portal host does not serve `/api/console/**` (404), and a portal session does not work on the console host (401). The same holds for staff and `/api/portal/**`.
- Linking two issues, the other issue (`issueKey`) is a target too, looked up at step 2 like the issue in the path. Only a missing `issueKey` (400 `REQUIRED`) comes before it, because there is nothing to look up. So an issue the caller cannot see is 404 even when `type` is wrong as well.

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

A 409 `RESTORE_LINK_CONFLICT` lists the other issues whose links stop the restore, by key, in `issueKeys`. Like `fields`, it is present only for that error:

```json
{
  "code": "RESTORE_LINK_CONFLICT",
  "message": "The issue's links break the link rules.",
  "issueKeys": ["ACME-12", "ACME-40"]
}
```

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
| 400 | `UNSUPPORTED_IMAGE` | The profile photo or the branding logo is not a PNG, JPEG, or WebP file, judged by its content (an SVG is not taken), or it cannot be decoded |
| 400 | `IMAGE_TOO_LARGE` | The profile photo or the branding logo has too many pixels: width times height over 25 million for PNG and JPEG, 12 million for WebP |
| 400 | `OWN_ACCOUNT` | An admin tries to suspend their own account or change their own role, or a customer manager tries to remove themselves from the company |
| 400 | `CUSTOMER_ROLE_FIXED` | A role change to `CUSTOMER`, or of a customer's account. Only `AGENT` and `ADMIN` change into each other |
| 400 | `LAST_ADMIN` | The suspension or role change would leave no `ACTIVE` admin |
| 400 | `ACCOUNT_NOT_ACTIVE` | The account is not `ACTIVE`, so its password cannot be reset by an admin; or it never accepted its invitation, so it cannot be suspended (cancel the invitation instead) |
| 400 | `INVITATION_NOT_ACCEPTED` | The account never accepted its invitation, so its suspension cannot be lifted. Send the invitation again instead |
| 400 | `INVITATION_ALREADY_ACCEPTED` | The account accepted its invitation before, so the invitation cannot be sent again or cancelled |
| 400 | `COMPANY_ARCHIVED` | The customer company is `ARCHIVED`: its status, name, and legal name no longer change |
| 400 | `COMPANY_NOT_ACTIVE` | Filing an issue: the customer company is not `ACTIVE`. Adding a member, an agent, a manager mark, an invitation, or a label: the customer company is `ARCHIVED` |
| 400 | `LABEL_LIMIT_EXCEEDED` | The customer company has 200 labels already, so no label can be created |
| 400 | `ISSUE_LABEL_LIMIT_EXCEEDED` | The issue has 20 labels already, so no label can be put on it |
| 400 | `MEMBER_IS_MANAGER` | A customer manager tries to remove another manager of the company. An admin does that |
| 400 | `INVITATION_NOT_RESENDABLE` | A customer manager tries to send again an invitation that cannot be sent again from the portal. One code whatever the reason |
| 400 | `NOT_A_CUSTOMER` | The account of a membership request is an agent or an admin, so the request cannot be approved. It can be rejected |
| 400 | `ISSUE_CLOSED` | The issue is `CLOSED`: no comments, attachments, tasks, task notes, links, or edits. For links, only the issue in the path counts: linking to or unlinking from a `CLOSED` issue works from an issue that is not closed. "No comments" covers adding a comment and editing one; deleting a comment still works. Tasks and task notes are the same: deleting them still works. Also when a customer closes an issue that is closed already. A status change by staff on a closed issue is `INVALID_TRANSITION` |
| 400 | `COMMENT_DELETED` | An edit of a comment that is deleted. Nothing changed |
| 400 | `TASK_LIMIT_EXCEEDED` | The issue has 30 tasks that are not deleted, or 100 with the deleted ones, so no task can be created |
| 400 | `TASK_NOTE_LIMIT_EXCEEDED` | The task has 100 notes that are not deleted, so no note can be added; or it has 1,000 entries (notes, deleted notes, and changes), so neither a note nor a change of its title, status, assignee, or due date is taken. Nothing changed |
| 400 | `TASK_DELETED` | An edit of a task that is deleted, or a note added to one. Nothing changed |
| 400 | `TASK_NOTE_DELETED` | An edit of a task note that is deleted. Nothing changed |
| 400 | `LINK_TO_SELF` | An issue linked to itself. Nothing changed |
| 400 | `LINK_DUPLICATE_CHAIN` | A duplicate link that would make a chain: the original is already a duplicate of another issue, or the duplicate is already the original of another issue or already a duplicate of another issue. A duplicate has one original. `RELATED` links are not checked. Nothing changed |
| 400 | `LINK_LIMIT_EXCEEDED` | One of the two issues has 50 links already, counting links to issues the caller cannot see. Nothing changed |
| 400 | `INVALID_TRANSITION` | A status change by staff: the issue cannot go from its current status to the one sent, or has that status already. Nothing changed |
| 400 | `NOT_REOPENABLE` | The issue is neither `RESOLVED` nor `CLOSED`, or it was closed 14 days ago or longer, so it cannot be reopened |
| 400 | `FILE_EMPTY` | The uploaded file has 0 bytes |
| 400 | `FILE_TOO_LARGE` | The uploaded file is over 10 MB |
| 400 | `ISSUE_ATTACHMENTS_TOO_LARGE` | The public attachments of the issue (on the issue and its public comments) would pass 50 MB |
| 400 | `ATTACHMENT_QUOTA_EXCEEDED` | All attachments of the team, temporary images included, would pass `POPDESK_ATTACHMENT_QUOTA` |
| 400 | `FILE_NOT_IMAGE` | The file uploaded as an image for an issue's content or a comment is not a PNG, JPEG, GIF, or WebP file, judged by its first bytes |
| 400 | `TEMPORARY_IMAGE_LIMIT_EXCEEDED` | The caller's temporary images (uploaded, not attached yet, and under 24 hours old) would pass 20 images or 50 MB |
| 400 | `TEMPORARY_IMAGE_INVALID` | An ID in `imageIds` of a request that saves an issue or a comment is not a temporary image the caller can attach: it is unknown, another account uploaded it, it is attached already, it is over 24 hours old, or it is an ordinary attachment. One body for all of these, and it does not say which ID. Nothing was saved |
| 400 | `CHANNEL_DISABLED` | Sending a failed outbox row again, or a test email: the channel (mail, webhook, or the outside copy of the audit log) is not configured, or `POPDESK_SANDBOX` turned it off. Nothing changed and nothing was sent |
| 401 | `UNAUTHENTICATED` | No session, or the session ended |
| 401 | `SIGN_IN_FAILED` | Sign-in failed. The same body whatever the reason: unknown email, wrong password, invited, suspended, or locked account, or an account of the other app |
| 403 | `FORBIDDEN` | The caller may not do this: the action is for other roles, or the caller can see the target but may not act on it |
| 403 | `CSRF_TOKEN_INVALID` | A request that changes data has no CSRF token or a wrong one |
| 404 | `NOT_FOUND` | The target does not exist or the caller may not see it (`COM-1`). Also a path the host does not serve |
| 409 | `COMPANY_CODE_TAKEN` | Another customer company has this code |
| 409 | `LABEL_NAME_TAKEN` | The customer company has a label of this name, whatever the case |
| 409 | `EMAIL_TAKEN` | An account has this email |
| 409 | `ALREADY_LISTED` | The email a customer manager invites is already a row of that company's member list |
| 409 | `TOO_MANY_PENDING` | The company already has 100 rows waiting in its member list (`INVITED`), so its manager cannot invite more |
| 409 | `REQUEST_CLOSED` | The membership request was closed the other way (approved when rejecting, rejected when approving), or its manager took it back |
| 409 | `LINK_EXISTS` | The two issues are linked already, of any kind and in either direction. Nothing changed |
| 409 | `RESTORE_LINK_CONFLICT` | Restoring an issue from the trash: with its links it would break the link rules (over 50 links, or a duplicate chain). `issueKeys` lists the other issues of the links at fault. The issue stays in the trash |
| 409 | `VERSION_CONFLICT` | The `version` sent with an edit of an issue is not the issue's current one: someone changed the issue in between. Or the `version` sent with a change of the SLA policy (`PUT /api/console/sla-policy`) is not the policy's current one: another admin changed it, or popdesk made a version for a new time zone; this is 409 also when the values sent are the ones the policy has now. Or the `version` sent with a change of the branding (`PUT /api/console/branding`) is not its current one, also with the values it has now; a new or removed logo does not change it. Nothing changed. Read the issue, the policy, or the branding again |
| 409 | `IDEMPOTENCY_KEY_REUSED` | The account used this `Idempotency-Key` in the last 24 hours for a filing with other content. Nothing is created |
| 409 | `AUDIT_VERIFICATION_RUNNING` | A check of the audit log's hash chain is already running. One runs at a time |
| 413 | `PAYLOAD_TOO_LARGE` | The request is over the app's upload limit, a profile photo is over 2 MB (2,097,152 bytes), a branding logo is over 512 KB (524,288 bytes), or the JSON of a new issue (the body, or the `issue` part of a multipart request) is over 256 KB (262,144 bytes). The headers of one multipart part, which carry the file name, are limited to 4 KB: a file name far over 255 characters gets this 413, not the 400 `TOO_LONG`. A proxy in front may answer 413 first with its own body |
| 429 | `RATE_LIMITED` | Over a rate limit (`COM-8`). `Retry-After` says when to try again |
| 500 | `INTERNAL_ERROR` | An unexpected server error |
| 501 | `NOT_IMPLEMENTED` | The endpoint is in the contract but not built yet. Only before a slice is finished; never in a release |
| 503 | `SERVER_BUSY` | 16 uploads, or 16 downloads, are already running, 2 images (profile photos and branding logos together) are already being decoded, or 2 audit log exports are already running. `Retry-After` says when to try again |
| 503 | `SETUP_REQUIRED` | The first admin could not be created, or `POPDESK_LOCALE` or `POPDESK_TIMEZONE` is not a value popdesk takes, so the app takes no requests yet. The log says what to set |

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
| `OUT_OF_RANGE` | A number or a date outside its range, for example `page` below 1, `size` above 100, or a due date before today |
| `TOO_MANY` | A list has more items than it may, for example over 10 mentions in a comment |
| `FROM_AFTER_TO` | The start of a period is after its end: `from` after `to` in the audit log filters. Reported on `from` |
| `NOT_ALLOWED` | The field must be left out here, for example companies when inviting staff |
| `SAME_AS_EMAIL` | The password is the same as the account's email |
| `LOW_CONTRAST` | The accent color of the branding makes a theme's `accent` under 4.5:1 on one of the backgrounds it is text on (ADR D-61). `previewAccent` gives the contrast before saving |
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

An image for an issue's content or a comment is uploaded before that issue or comment is saved, so it has no issue yet. Until a save attaches it, it is a temporary image with a path of its own:

```
/api/portal/images/{id}
/api/console/images/{id}
```

- Only the account that uploaded a temporary image reads it there. Nobody else does, not an admin and not a colleague of the same company.
- A temporary image is kept for 24 hours. A request that saves an issue or a comment and names the image's ID in `imageIds` attaches it; an image that is not attached by then is deleted and leaves no trace.
- Once attached, the image is an attachment of that issue with the same ID. It is addressed under the issue like every other attachment, by the rules above, and its temporary path answers 404.
- The temporary path (download, `HEAD`, range requests) answers 404 with the same status, headers, and body in all of these cases: the ID does not exist or is not a UUID in its 36-character form, another account uploaded the image, the image is attached already, it is over 24 hours old (also while it is not deleted yet), or it is still being written or already deleted.
- Uploads and downloads of temporary images take places of the same 16 uploads and 16 downloads.

## Member list of a customer manager

`GET /api/portal/companies/{code}/members` lists two kinds of rows as one list: customers who belong to the company, and membership requests that wait for an admin (a manager invited an email that already has an account). A manager must not be able to tell them apart, because that would tell whether an email has an account at another company (`MGR-3`).

- A row is named by `id`, a random UUID made for that row. It is not an account ID and is not derived from one. `DELETE …/members/{id}` and `POST …/members/{id}/resend` take it. An `id` of another company, an unknown one, or text that is no UUID is 404.
- The row of a membership request has the name and email the manager typed and the status `INVITED`, with the same fields as the row of a new account. When an admin approves the request, the row keeps its `id` and shows the account's own name and status from then on. When an admin rejects it, the row is gone.
- Both kinds are sorted together by email, ignoring case, and paged together.
- Two cases have the same answer wherever one of them would give the account away: the same status, body, and headers.

| Request | The two cases | Answer |
|---|---|---|
| Invite | a new email; an email that has an account | 204, no body |
| Invite to an `ARCHIVED` company, or with 100 invited rows | the same two | 400 `COMPANY_NOT_ACTIVE`, 409 `TOO_MANY_PENDING` |
| Remove a row | a member; a membership request | 204, no body |
| Send again | an invited member of this company alone; a membership request (nothing is sent) | 204, no body |

- 409 `ALREADY_LISTED` is given only for an email that is already a row of this list, which the manager can see anyway. The email is compared without case and without the spaces around it.
- Response times are not made equal.

## Filing an issue once

The two requests that file an issue (`POST /api/portal/issues`, `POST /api/console/issues`) take an optional `Idempotency-Key` header, so that a request sent again after a lost answer does not file a second issue (`ISSUE-12`).

- The key is 1 to 100 visible ASCII characters (`!` to `~`); anything else is 400. Clients make a new random key for each filing, keep it when they send the same filing again, and make a new one when the input changes.
- A key belongs to the account that sent it. Another account that sends the same key files its own issue.
- For 24 hours, a request with a key the account already used and the same content creates nothing: the answer is 201 with the issue the first request made, as it is now.
- The same key with other content is 409 `IDEMPOTENCY_KEY_REUSED`. The content compared is the company, title, content, priority, and whether the issue is private; for a filing on behalf also the customer and the channel. Files are not compared.
- Two requests with the same key at the same time file one issue and use one issue number.
- A key is kept only for a filing that succeeded. After a 400, 404, or 429 the same key can be sent again with the input put right.
- The checks keep their order: the company (404) and the fields (400) come before the key is looked at, so a request with a used key and a field that is invalid is 400, not 409. A filing sent again is answered even if the company has stopped being `ACTIVE` since.
- A request sent again counts toward the limit on creating issues like any other, and can be 429.
- The issue in the answer is read like any issue: if the account can no longer see it (an agent taken off the company), the answer is 404.
- Without the header every request files a new issue.

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

Some lists are short by rule and come all at once, as `{"items": [...]}` without paging, for example the links of an issue (at most 50), the labels of a company (at most 200), and the issue counts per company (`GET /api/console/stats`, one item per company the caller can see).

The issue counts per company take `days`: 7, 30 (the default), or 90. Any other value is 400 with `days` `UNKNOWN_VALUE`. It changes only each item's `sla`, which counts the SLA goals that stopped within the last `days` × 24 hours: for first response and for resolution, `met`, `missed`, and `medianMinutes`, the lower median of the business time taken. The other counts are as they are now whatever `days` is. While the SLA policy is off, `sla` is `null` in every item.

The staff issue list also takes `sort`. Every order ends with a key that is unique to each issue, so issues with the same due date and the same filing time neither repeat nor go missing across pages:

| `sort` | Order |
|---|---|
| `createdAt` (default) | Newest first, then by issue number, newest first |
| `dueDate` | Earliest due date first, issues without a due date last; equal due dates in the `createdAt` order |
| `slaDue` | Earliest running SLA due time first, the earlier of the issue's two goals; issues without a running SLA goal last; equal times in the `createdAt` order |

`dueDate` is the due date people set on an issue (DUE-1); `slaDue` is the due time popdesk works out from the SLA policy. They are different values.

The staff issue list also takes `sla`: `breached` for issues with a running SLA goal past its due time, `warning` for issues with one past 80% of the goal and none breached. A goal whose time was used up when its clock stopped is past its due time from the moment the issue is reopened or restored. An issue counts by the worse of its two running goals. Issues not under SLA, and issues whose SLA clocks are stopped, match neither. Any other value is 400 with `sla` `UNKNOWN_VALUE`. The customer issue list takes neither `sla` nor `sort=slaDue`: customers never see SLA.

Any other value of `sort` is 400 with `sort` `UNKNOWN_VALUE`. `sort` changes only the order: the filters, `totalElements`, and `totalPages` stay the same. Lists that do not take `sort` answer it with `UNKNOWN_PARAMETER` (the customer issue list, for example).

## Search (COM-5)

- The search parameter `q` is 1 to 100 characters. On the issue lists and the list of customers staff can file for it is a one-line value ("Text input"): the whitespace around it is removed before it is counted and searched, so a `q` of spaces only is empty. An empty `q` is 400 with `q` `TOO_SHORT`; one over 100 characters is `TOO_LONG`.
- Matching ignores case and finds the text anywhere in the field, not by word.
- `%`, `_`, and `\` are ordinary characters, not wildcards.
- Search uses a trigram index (D-13).
- The issue lists search the title and the content as it is shown: the text of a link, the description of an image, and the text of code are searched; Markdown marks (`**`, `#`, list marks, code fences) and the addresses of links and images are not. Raw HTML in the content is searched as written.

## Text input

- Lengths are counted in Unicode code points.
- All string input is normalized to NFC before it is checked and stored.
- One-line values (titles, names) have leading and trailing whitespace removed before they are checked and stored. Multi-line text (issue content, comments) is not trimmed.
- Text with a NUL character (U+0000) or a surrogate without its pair (a code unit from U+D800 to U+DFFF that is not half of a high-low pair) is refused: 400 `INVALID_INPUT` with that field `INVALID_FORMAT`. Neither can be stored or shown. A surrogate pair, which is one character outside the basic plane such as an emoji, is taken as it is.

## Markdown content (COM-6)

Issue content and comments are stored as the Markdown the author wrote. Clients render it with an allow list: no scripts, no event attributes, no `javascript:` links.

- An image is shown only when it is written as `![description](attachment:{id})`, with `{id}` the 36-character UUID of an attachment. The client builds the address from the ID: the attachment path of the issue, or the temporary path for an image the author uploaded and has not saved yet ("Attachment paths"). The description is the image's alternative text.
- Image syntax with any other address (`http:`, `https:`, `data:`) and raw `<img>` are not shown as images. They stay text, so opening an issue never fetches anything from another server (D-59).
- The server does not read image IDs out of the content. A save attaches the images its `imageIds` names and no others, whatever the content says; the content is stored as written either way.
- Taking an image out of the content does not delete it. It stays an attachment of the issue until someone deletes it.

## Deletion that leaves a trace (COM-7)

Some deletions keep the row and leave a trace: the content fields are cleared, and the row records `deleted`, the time of deletion, and who deleted it. Lists show the item in its place as "Deleted".

- An attachment: `fileName` becomes `null`.
- A comment: `content` becomes an empty string, and the comment's attachments are deleted with it. The comment stays in its place in the list and is counted. The portal gives `deleted` and `deletedAt` but not who deleted it; the console gives `deletedBy` too. A deleted comment takes no new attachment: its ID as `commentId` of an upload is `UNKNOWN_VALUE`, like the ID of a comment that is not the caller's.
- A task (console only): `title` becomes an empty string, `assignee` and `dueDate` become `null`, `overdue` is `false`, and `notes` is `[]`. Its notes are deleted with it, and its changes lose their values. Deleting it again is 204 and changes nothing.
- A task note (console only): `content` becomes an empty string. Deleting it again is 204 and changes nothing.

An issue is deleted in two steps. Deleting it (`deleteIssue`, admins only) moves it to the trash and changes nothing in it, so `restoreIssue` brings it back as it was. Purging it, by an admin (`purgeIssue`) or by popdesk 30 days after the delete, deletes what was written: the title and content become empty strings, and its comments, attachments and their files, tasks, links, labels on it, and inbox items are deleted. The issue's row (key, company, status, times) and its history entries stay, with the values of `TITLE` and `CONTENT` entries cleared. A purged issue is answered 404 everywhere and its number is never used again. The history field `TRASH` records each step: new value `TRASHED` on a delete, old value `TRASHED` on a restore, and `TRASHED` to `PURGED` on a purge.

## Notification inbox (INBOX-3)

Each app has the inbox of the signed-in account: `/api/portal/notifications` and `/api/console/notifications`, with the same four requests (the list, `unread-count`, `{id}/read`, `read-all`).

- A caller gets only their own items, newest first, paged (`COM-4`). Nobody else gets them: not a colleague of the same company, not another agent who sees the same issue.
- An item about an issue is listed only while the caller can see that issue. When the caller stops seeing the issue (an agent taken off the company, a customer who left it), the item is left out; it is not deleted, and it is back when the caller can see the issue again. The issue's title is read when the list is, so it is the current title.
- The unread count follows the same rule: the items the list would show that have no `readAt`.
- Marking an item read sets `readAt` once; marking it again is 204 and keeps the first time. An item of another account, or one that does not exist, is 404.
- `read-all` marks every unread item of the caller read, also those left out of the list now, so they do not come back as unread later.
- Deleting a comment deletes the items about that comment, and deleting a task deletes the items about that task. Taking an account out of a comment's mentions deletes that account's unread item about the mention.
- An item is made in the request that causes it, for one person: the reporter for what staff do (a public comment, resolving, closing, reopening, filing for them), the assignee for what the reporter does (a comment, closing, reopening), the new assignee, and each account a comment mentions. Nobody gets an item for what they did themselves, an account that is not active or cannot see the issue at that moment gets none, and a person a comment both mentions and would be told of gets the mention alone. So an issue without an assignee tells nobody of the customer's comment. A request that fails or changes nothing makes no item.
- A task's new assignee gets an item `TASK_ASSIGNED` that names the task, unless they set it themselves. Clearing a task's assignee, notes, and changes of its status, due date, or title tell nobody. Customers never get an item about a task.
- When an issue becomes `RESOLVED` or `CLOSED`, the assignee of each issue linked to it gets an item `LINKED_ISSUE_RESOLVED` or `LINKED_ISSUE_CLOSED`. The item's `issue` is the assignee's own issue and `linkedIssue` the one that changed. No email goes with it. Nobody gets one who cannot see both issues, whose own issue is `CLOSED`, who made the change, or who is not active, and an issue without an assignee tells nobody. While the same item is still unread, another one of the same kind about the same two issues is not made. The item is listed only while the caller can see both issues. Customers never get an item about a link.
- The portal's items have their own type and their own kinds, only those a customer gets. What an agent or an admin did has the actor `SUPPORT_TEAM`, without a name, an email, or an account ID (D-49). The console gives the actor's account ID, name, and role.
- SLA alerts are made by popdesk as time goes by, once for each goal and step: `SLA_RESPONSE_WARNING` and `SLA_RESOLUTION_WARNING` when a running goal passes 80%, `SLA_RESPONSE_BREACHED` and `SLA_RESOLUTION_BREACHED` when it passes its due time. They go to the assignee when the assignee is active and can see the issue, else to every active agent assigned to the company, else (a warning only) to every active admin; an overdue item also goes to every active admin, once per person. Each overdue item comes with an email, and with one webhook message per goal when the webhook is on, in the same form as the other messages: "SLA first response overdue" or "SLA resolution overdue", the issue key, the company code, and the title, then the console link. Customers never get an SLA item, email, or message. The actor is `null`. An issue in the trash, a stopped clock, and a goal that is not set are not alerted. When working the due times out again after a policy change makes an issue overdue or due soon, that issue is counted in the summary below and its goals are not alerted on their own. A goal that comes to a later step while the issue's own step stays (its other goal was overdue already, or moved back) is alerted as above. An alerted step stays alerted while the clock is stopped or the issue is in the trash, so a reopened or restored issue that is still overdue is not alerted again; a goal that moves back to an earlier step (a lower priority, a longer goal) is alerted again when it comes to that step again.
- `SLA_RECALCULATED` goes to every active admin once working the SLA due times out again has finished and changed something. Its `slaSummary` has three counts of changes to issues, each judged by the worse of the issue's two running goals: `becameBreached`, `becameWarning`, and `released`. Every other kind has `null` for `slaSummary`. No email or webhook message goes with it.
- Items about no issue (operational alerts, membership requests, `SLA_RECALCULATED`) go to admins only and have `null` for the issue, the comment, and the actor.
- The kinds of operational alert are `DELIVERY_FAILED`, `ATTACHMENT_QUOTA_WARNING`, `MAIL_STALLED`, `WEBHOOK_STALLED`, `AUDIT_FORWARD_STALLED`, and `CLEANUP_STALLED` (a cleanup job has not succeeded for its interval plus 24 hours). Each goes to every active admin. While one is not resolved it is not made again, except once every 24 hours; when it is resolved, the next one is made at once ([configuration.md](../operate/configuration.md)). An alert names no job, channel, or row; the console's system status shows them.
- What popdesk itself did, such as closing a `RESOLVED` issue after 7 days, has `null` for the actor in the console's items too.
- Nothing is sent about an issue in the trash: no inbox item, email, or webhook message. Deleting an issue deletes its waiting messages, so restoring it sends none of them. Its inbox items are kept, left out of the list and the unread count, and listed again after a restore.

## Rate limits (COM-8)

Limits are per account over a 60-second window:

| Action | Limit |
|---|---|
| Create an issue | 10 |
| Add or edit a comment | 30 (both count together; deleting a comment is not counted) |
| Create or edit a task, add or edit a task note | 60 (all four count together; deleting is not counted) |
| Link issues and remove links | 60 (both count together, also requests that end in 400, 404, or 409) |
| Upload an attachment | 30 (each file of a new issue counts as one; a new issue whose files do not all fit is refused whole). An image uploaded for an issue's content or a comment counts here too, as one upload |
| Upload a profile photo | 10 |
| Upload a branding logo | 10 (counted apart from profile photos, also uploads refused with 400 or 413) |
| List with a search term | 60 (the issue lists, the account list, and the list of customers staff can file for count together). A list without `q` is not counted, whatever other filters it has |
| Customer manager sends an invitation, or sends one again | 20 (both count together) |
| Admin sends a test email | 5 |
| Sign-in attempts | 20 per IP address |
| Password reset requests | 20 per IP address; at most one reset email per account every 15 minutes |

- Over the limit, the response is 429 with a `Retry-After` header and the error body.
- Counts are kept in the app's memory; popdesk runs one app instance (D-23).
- The count is taken right after the sign-in check and before the target is looked up, so a 429 never tells whether a target exists.
- Issues and comments created from incoming email count against the sender's account in the same way. Email over the limit is not turned into issues or comments; it is moved to the quarantine folder.

## Time and time zone (TIME-1)

- Timestamps are stored in UTC and returned as ISO-8601 with an offset, in UTC: `2026-10-08T03:15:00Z` (D-10).
- Timestamps sent to the API must include an offset. A timestamp without one is 400.
- Date-only values (for example due dates) are `YYYY-MM-DD`. "Today" for these values is judged in the installation time zone (`POPDESK_TIMEZONE`, default `UTC`), so two installations in different zones can answer the same date differently at the same moment. The client never judges "today" itself: issues carry `overdue`.
- `POPDESK_TIMEZONE` takes an IANA zone name (`Asia/Seoul`) or `UTC`. With an empty value or any other value, such as `KST` or `+09:00`, the app takes no requests and answers `SETUP_REQUIRED` until the setting is fixed (see `docs/operate/configuration.md`).

- Periods (24 hours, 7, 14, or 30 days, retention periods) are measured as a difference of UTC times. Daylight saving time and changes to `POPDESK_TIMEZONE` do not change their length. The installation time zone is used only to judge dates: due dates and SLA business hours and holidays.
- SLA goals count business time: the wall-clock span of each weekday in the installation time zone, without holidays. On a day daylight saving time starts or ends, the span counts the time that really passes in it. SLA due times are returned as UTC timestamps like any other, and the server gives each goal's state and remaining minutes, so the client never counts business time itself.

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
- A change of the SLA policy (`SLA_POLICY_CHANGED`) has the target `{"type": "SLA_POLICY", "version": n}`, the new version. Its `changes` hold each changed part (`targets`, `hours`, `holidays`) as whole lists, before and after, in the shapes of the `SlaPolicy` response: times as `"HH:MM"` text and days as `"YYYY-MM-DD"` text. A version popdesk makes for a new time zone leaves no entry.
- A change of the branding's display name or accent color (`BRANDING_CHANGED`) has the target `{"type": "BRANDING"}`. Its `changes` hold only what changed, `displayName` and `accent` (the `#RRGGBB` text, or `null` for the apps' own colors), always as `{"from": …, "to": …}`: the installation starts with a branding, so no value is set for the first time. Saving what the branding has already leaves no entry.
- A new or removed logo (`BRANDING_LOGO_CHANGED`) has the target `{"type": "BRANDING", "theme": "LIGHT"}` or `"DARK"`, and in `changes` `logo` as `{"from": …, "to": …}`: the first 16 characters of the SHA-256 of the stored PNG before and after, or `null` without a logo. The file name and the file are never in it. Uploading the same logo again, and removing a logo that is not there, leave no entry.
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
- An entry still failing 24 hours after its first failure is given up and not sent again; the console's system status lists it with its `auditEntryId`, and `resendFailedDelivery` sends it again until it is deleted 7 days later. Fill a gap from an export: its lines have the same fields.
- The outside copy is off while `POPDESK_SANDBOX=true`.
