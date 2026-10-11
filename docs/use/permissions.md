# Permissions

Who can see and do what in popdesk. This is the canonical permission model: code, tests, and other documents follow it. Rule IDs in parentheses (for example `ISSUE-6`) point to the builders' notes in `design/`.

popdesk is not released yet. Each feature follows this table from the slice that ships it.

## Roles

| Role | App | Who |
|---|---|---|
| Customer | Customer portal | A person at a customer company. Belongs to one or more customer companies |
| Customer manager | Customer portal | A customer whom an admin marked as manager of one of their companies. Not a separate role: the mark belongs to one membership, so a person can be manager of company A and an ordinary customer of company B |
| Agent | Agent console | Support staff. Works on the customer companies an admin assigned to them |
| Admin | Agent console | Support staff who also manage the installation. Sees every customer company |
| System | – | Scheduled jobs (auto-close, purge, retention). Recorded as account `0` |

"Staff" below means agents and admins. "Reporter" is the customer an issue was filed for, including issues an agent filed on their behalf (`ISSUE-9`).

A customer cannot sign in to the console, and staff cannot sign in to the portal. A sign-in on the wrong app fails with the same response as a wrong password (`AUTH-1`).

## The customer-company boundary

These rules hold for every path: portal, console, API, email notifications, inbound email, webhooks, and exports.

1. A customer never sees issues, comments, attachments, or customer names of a company they do not belong to (`RULE-01`).
2. A customer sees public issues of the companies they belong to, and every issue they reported (`RULE-02`). After leaving a company they still see the issues they reported there.
3. A private issue is visible to its reporter and to staff only (`ISSUE-4`).
4. An agent sees all issues, public and private, of the companies assigned to them. An admin sees all issues (`RULE-03`). When an agent is unassigned, they lose access at once; the assignee field on existing issues stays as it was (`PROJ-4`).
5. Being a customer manager does not widen what a person sees among issues (`MGR-5`).
6. Customers never see internal information: tasks, task notes, internal comments and their attachments, assignee, labels, due dates, issue history, SLA state, and which staff member filed an issue on their behalf. Everywhere customers can look (the portal, the portal API, emails, and notifications), staff members appear only as "Support team": comment authors, people who uploaded attachments, and people who filed an issue on a customer's behalf. Portal API responses never contain a staff member's name, email, or account ID (`RULE-04`, `INBOX-1`, ADR D-49). Customer list requests cannot filter or sort by internal fields (`ISSUE-5`).
7. Name lookups follow the same boundary: a customer sees the names of staff, of themselves, and of customers in the companies they belong to (`ACCT-4`).

Customer memberships, agent assignments, and the customer-manager mark are read from the database on every request, not kept in the session. A change applies from the next request (`AUTH-4`).

Every feature that returns customer data ships with a negative test: a customer of company A cannot see anything of company B.

### Not found or forbidden

| Situation | Response |
|---|---|
| Not signed in | 401 |
| The target is outside what you may see, does not exist, or is in the trash | 404. popdesk does not reveal that it exists (`COM-1`) |
| You can see the target but may not do this | 403 (`COM-2`) |
| The action is for other roles only, whatever the target (for example a customer calling a task endpoint) | 403, checked before the target |

Order of checks: sign-in (401) → target in scope (404) → permission (403) → input (400) → state (400, 409).

## Accounts and sign-in

| Action | Customer | Agent | Admin |
|---|---|---|---|
| Change own name, language, password | Yes | Yes | Yes |
| List accounts, filter, search | – | – | Yes |
| Edit someone's name, suspend or reactivate, reset password | – | – | Yes, not own suspension |
| Change role between agent and admin | – | – | Yes, not own role. A customer can never become staff or the reverse |
| Invite staff | – | – | Yes |
| Invite customers | Manager: to their company only (see below) | – | Yes, with at least one company |
| Resend or cancel an invitation | Manager: resends, and cancels by removing (see below) | – | Yes, for accounts that have not accepted yet. Resending revives a cancelled invitation |
| Export one person's data | – | – | Yes (`PII-3`) |
| Anonymize an account | – | – | Yes, not self, not the last admin (`PII-4`) |

## Customer companies

| Action | Customer | Agent | Admin |
|---|---|---|---|
| List companies | Their own | Assigned to them | All |
| Create, edit, change status | – | – | Yes. The code never changes. `ARCHIVED` is final: no status change, no rename |
| Add or remove customer memberships | Manager: adds only by inviting, removes (see below) | – | Yes. Nothing can be added to an `ARCHIVED` company |
| Assign or unassign agents | – | – | Yes. Not to an `ARCHIVED` company |
| Mark or unmark a customer manager | – | – | Yes |
| Approve or reject membership requests | – | – | Yes |
| Create, edit, delete labels | – | – | Yes |
| Export a company's data | – | – | Yes (`PII-8`) |

### Customer manager

For the company where they are manager, a customer manager can:

- See the members of that company: name, email, status, manager mark.
- Invite a customer by email and name. If the email is new, an invitation is sent and the person joins the company. If the email already has an account, the account is not touched and an admin gets a membership request. Both cases return the same response and show the same invited row in the member list, so a manager cannot learn whether someone has an account at another company (`MGR-3`). If an admin rejects the request, the row disappears as if the invitation were cancelled.
  - The name takes no control characters such as a line break or a tab, because it goes into the invitation email.
  - A manager can send up to 20 invitations a minute, counting the ones sent again.
  - A company can have at most 100 invited rows at a time: customers who have not accepted yet and membership requests that wait for an admin, together. With 100, the next invitation is refused until one of them accepts, is approved, or is removed.
  - Nobody can be invited to an `ARCHIVED` company.
- Remove a row from the member list. Not their own, not another manager's. For a member this takes away the membership of that company only: the account is not suspended and its other memberships stay. For a row that is still invited there is no separate "cancel": removing it is how a manager cancels.
  - If the invited person belongs to that company alone, removing also cancels the invitation, so the link stops working.
  - If the invited person belongs to another company too, the invitation stays and they still join the other company.
  - If the row is a membership request, the request is withdrawn and the admins no longer see it.
- Send an invitation again, only if the invited customer belongs to that company alone and the invitation is still open. Otherwise it is refused with one error, whatever the reason: the customer accepted already, is suspended, has an invitation an admin cancelled, or belongs to another company too.

A manager who removes an invited person and invites the same email again gets a membership request, because the account still exists. An admin approves it and sends the invitation again from the console; approving alone does not reopen a cancelled invitation.

Everything a manager does goes to the audit log.

## Issues

"Agent" cells in this and the following sections apply only to issues of companies assigned to that agent. A customer who left the company still sees the issues they reported and can close them; editing, commenting, attaching, and reopening return 403 (`PROJ-4`).

| Action | Reporter | Colleague (same company, not reporter) | Agent | Admin |
|---|---|---|---|---|
| See a public issue | Yes | Yes | Yes | Yes |
| See a private issue | Yes | No (404) | Yes | Yes |
| File an issue | Yes, in an active company they belong to | – | On behalf of a customer only | On behalf of a customer only |
| Edit title, description, priority | Yes | No (403) | Yes | Yes |
| Set or clear assignee | – | – | Yes | Yes |
| Change status (staff transitions) | – | – | Yes | Yes |
| Close from any open status, confirm a resolution | Yes | No (403) | Through status changes (not from `IN_PROGRESS`) | Through status changes (not from `IN_PROGRESS`) |
| Reopen a `RESOLVED` issue, or a `CLOSED` one within 14 days | Yes | No (403) | Yes | Yes |
| Add or remove labels (of that company), set a due date (today or later) | – | – | Yes | Yes |
| See issue history | – | – | Yes | Yes |
| See SLA state and remaining time | – | – | Yes | Yes |
| See the trash, delete, restore, purge | – | – | – | Yes |

On a `CLOSED` issue nothing can be edited, commented on, attached, labelled, or given a due date (400); tasks and notes are read-only.

Assignee: an active admin, or an active agent assigned to that company (`ISSUE-7`).

Filing on behalf (`ISSUE-9`): the customer must be an active customer account that belongs to that company, and the company must be active (otherwise 400). The issue records the customer as reporter and the staff member as creator.

## Comments and mentions

| Action | Reporter | Colleague | Agent | Admin |
|---|---|---|---|---|
| Read public comments | Yes | Yes | Yes | Yes |
| Add a public comment | Yes | No (403) | Yes | Yes |
| Read or add internal comments | No (internal comments do not exist for customers: 404) | No (404) | Yes | Yes |
| Edit a comment | Own only | – | Own only | Own only |
| Delete a comment | Own | – | Own | Any |

A mention can name up to 10 active accounts who can see the issue: admins, agents assigned to the company, and, in a public comment, the reporter. Not yourself. Anyone else is 400 (`MNT-1`).

## Attachments

- Upload: anyone who can comment on the issue. An attachment on a comment can be added only by that comment's author.
- Download: anyone who can see the issue. For customers, attachments of internal comments do not exist: they are not listed and downloading them returns 404.
- Inline images: a temporary image can be read only by the person who uploaded it until it is attached to an issue or comment. Only your own, unattached, unexpired temporary images can be attached (`ATT-6`, `ATT-7`).
- Delete: the uploader, or an admin.

## Tasks and task notes

Staff only. Customers get 403 for every task endpoint and never see tasks anywhere (`TASK-1`). A task's assignee must be an active admin or an active agent assigned to that company, as for issues (`TASK-6`).

| Action | Agent | Admin |
|---|---|---|
| Create a task; edit its title, status, assignee, due date; add notes | Yes | Yes |
| See "My tasks" across issues | Own tasks, on issues they can still see | Own tasks |
| Delete a task | Own | Any |
| Edit a note | Own | Own |
| Delete a note | Own | Any |

## Linked issues

Staff can link two issues as duplicate or related, even across customer companies (`LINK-1`).

- Linking or unlinking requires that you can see both issues; otherwise 404.
- Customers never see links: not in the portal, the API, email, or company exports (`LINK-3`).
- Staff see only the linked issues they can see. An agent assigned to company A does not see, or count, a link to an issue of company B (`LINK-4`).
- Notifications about a linked issue go only to assignees who can see it (`LINK-6`).

## Other areas

| Area | Customer | Agent | Admin |
|---|---|---|---|
| Notification inbox | Own items, only for issues they can still see | Same | Same |
| Statistics | – | Assigned companies | All companies |
| SLA policy and business hours | – | – | Edit |
| Branding | Read (public, also before sign-in) | Read | Edit |
| Audit log: view, export, integrity check | – | – | Yes |

## Email

Inbound email follows the same rules as the portal. Only active customer accounts are accepted. A reply becomes a comment only if the sender can see the issue and could add that comment in the portal; otherwise the sender gets an explanation and a portal link, which never names an issue the sender cannot see (`MAILIN-1`, `MAILIN-2`). A new email becomes an issue only in an active company the sender belongs to, chosen by the `+CODE` address or by the sender's only membership (`MAILIN-3`).

Outbound email links go to the portal for customers and to the console for staff. Notification emails to customers never contain internal information. Each email is checked again just before it is sent: if the issue is in the trash or the recipient can no longer see it, it is not sent (`MAIL-4`).
