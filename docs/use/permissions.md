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
| The action is for other roles only, whatever the target (for example an agent calling an endpoint for admins only) | 403, checked before the target |
| A customer calls a console endpoint (a task endpoint, for example) | 404 on the portal host, which does not serve it; 401 on the console host, where a portal session does not work |

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
| List a company's labels | – | Assigned to them | All |
| Create, edit, delete labels | – | – | Yes. At most 200 labels in a company. None can be created in an `ARCHIVED` company; its labels can still be edited and deleted |
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
| Set or clear assignee, list who can be assigned | – | – | Yes | Yes |
| List the customers of a company to file for | – | – | Yes | Yes |
| Filter the list by status, company, search text | Yes | Yes | Yes | Yes |
| Filter the list by reporter | Own issues only | Own issues only | Yes | Yes |
| Filter the list by assignee | – | – | Yes | Yes |
| Change status (staff transitions) | – | – | Yes | Yes |
| Close from any open status, confirm a resolution | Yes | No (403) | Through status changes (not from `IN_PROGRESS`) | Through status changes (not from `IN_PROGRESS`) |
| Reopen a `RESOLVED` issue, or a `CLOSED` one within 14 days | Yes | No (403) | Yes | Yes |
| Add or remove labels (of that company, at most 20 on an issue), set or clear a due date (today or later in `POPDESK_TIMEZONE`) | – | – | Yes | Yes |
| Filter the list by label, sort it by due date | – | – | Yes | Yes |
| See issue history | – | – | Yes | Yes |
| See SLA state and remaining time | – | – | Yes | Yes |
| See the trash, delete, restore, purge (see [Trash](#trash-and-closing-resolved-issues)) | – | – | – | Yes |

On a `CLOSED` issue nothing can be edited, commented on, attached, labelled, or given a due date (400); tasks and notes are read-only. Its assignee can still be set and cleared.

A filter or a search text only narrows what the caller sees. The code of a company the caller cannot see, or an account that does not exist, gives an empty list, not an error.

Assignee: an active admin, or an active agent assigned to that company (`ISSUE-7`). An assignment to another company does not count. The list of who can be assigned gives the account ID, name, and role of those accounts, to staff who see the company.

When an agent is taken off a company, or an account is suspended, the issues they are assigned to keep them as assignee (`PROJ-4`). Such an issue is marked "assignee unavailable" for staff, and the assignee filter finds these issues. Before either action the admin is shown how many open issues the person is assigned to (for taking an agent off a company, those of that company only) and can choose to clear them in the same request (`ACCT-7`). Clearing leaves `CLOSED` issues as they are, writes an entry by the admin into the history of each cleared issue, sends no webhook message, and puts the number of cleared issues into the audit entry of the action.

Filing on behalf (`ISSUE-9`): the customer must be an active customer account that belongs to that company, and the company must be active (otherwise 400). The issue records the customer as reporter and the staff member as creator. To pick the customer, staff read a list of the company's active customers with account ID, name, and email, and can search it by name or email. An agent reads it for assigned companies only (404 for others). Customers who have not accepted their invitation and suspended ones are not in it. The member list of a company, with statuses and manager marks, stays with admins and the company's manager.

Customers never see the assignee, the staff member who filed an issue for them, how the request came in, or the history. An issue filed for them only says that the support team filed it.

## Comments and mentions

| Action | Reporter | Colleague | Agent | Admin |
|---|---|---|---|---|
| Read public comments | Yes | Yes | Yes | Yes |
| Add a public comment | Yes | No (403) | Yes | Yes |
| Read or add internal comments | No (internal comments do not exist for customers: 404) | No (404) | Yes | Yes |
| Edit a comment | Own only | No (403) | Own only | Own only |
| Delete a comment | Own | No (403) | Own | Any |
| Mention someone, list who can be mentioned | No | No | Yes | Yes |

- Editing: only the author, an admin included; anyone else who can see the comment gets 403. Not on a `CLOSED` issue (400 `ISSUE_CLOSED`) and not a deleted comment (400 `COMMENT_DELETED`). Whether a comment is public or internal cannot be changed (`CMT-3`, `CMT-4`).
- Deleting: the author, or an admin. An agent cannot delete another person's comment, and a customer cannot delete a staff comment (403). It works on a `CLOSED` issue too. The comment stays in the list as a deleted comment without its content, its attachments are deleted with it, and the inbox items about it are removed. A customer is not told who deleted a comment.
- A reporter who no longer belongs to the company can still read their issue, but cannot edit or delete their comments on it (403), as they cannot add one (`PROJ-4`).
- A comment on an issue the caller cannot see, a comment that does not exist, and for a customer an internal comment, are all 404.

Mentions are for staff only. A customer cannot mention anyone, and the portal never says who a comment mentions. A mention can name up to 10 active accounts who can see the issue: admins, agents assigned to the company, and, in a public comment, the reporter. Not yourself. Anyone else is 400, the same answer whatever the reason (`MNT-1`). When a comment is edited only the accounts new to its mentions are checked and told; an account the comment mentions already may stay in the list after it was suspended or taken off the company, and then its inbox does not show the item.

## Attachments

- Upload: anyone who can comment on the issue. An attachment on a comment can be added only by that comment's author.
- Download: anyone who can see the issue. For customers, attachments of internal comments do not exist: they are not listed and downloading them returns 404.
- Inline images: a temporary image can be read only by the person who uploaded it until it is attached to an issue or comment. Only your own, unattached, unexpired temporary images can be attached (`ATT-6`, `ATT-7`).
- Delete: the uploader, or an admin.

## Tasks and task notes

Staff only. Customers never reach a task endpoint: like every console endpoint, it is 404 on the portal host and 401 on the console host. Tasks appear nowhere a customer looks: not in the portal, its API, its notifications, or email (`TASK-1`). A task's assignee must be an active admin or an active agent assigned to that company, as for issues (`TASK-6`). Tasks follow the issue: an agent who cannot see the issue gets 404 for its tasks.

| Action | Agent | Admin |
|---|---|---|
| Create a task; edit its title, status, assignee, due date; add notes | Yes | Yes |
| See "My tasks" across issues | Own tasks, on issues they can still see and that are not closed | Own tasks, on issues that are not closed |
| Delete a task | Own | Any |
| Edit a note | Own | Own |
| Delete a note | Own | Any |

- On a `CLOSED` issue, tasks and notes can be read and deleted, not created or edited. Reopening the issue makes them editable again.
- A deleted task or note stays in the list as a trace: who deleted it and when. A deleted task loses its title, assignee, due date, and notes. A deleted task or note cannot be edited.
- The changes popdesk writes into a task's notes (status, assignee, due date, title) cannot be edited or deleted.
- Limits: 30 tasks per issue that are not deleted (100 with the deleted ones); 100 notes per task that are not deleted (1,000 entries with the deleted notes and the changes); 60 creations and edits of tasks and notes per account in 60 seconds.

## Linked issues

Staff can link two issues as duplicate or related, even across customer companies (`LINK-1`).

- Linking or unlinking requires that you can see both issues; otherwise 404.
- Customers never see links: not in the portal, the API, email, or company exports (`LINK-3`).
- Staff see only the linked issues they can see. An agent assigned to company A does not see, or count, a link to an issue of company B (`LINK-4`).
- An issue has at most 50 links, counting links to issues you cannot see (`LINK-2`). Two issues have one link at most, of any kind.
- A duplicate has one original, and an issue that is a duplicate cannot be an original: duplicates do not form chains (`LINK-2`).
- When the issue you link from or unlink from is `CLOSED`, you cannot link or unlink it. The other issue may be closed: a new issue can be linked as a duplicate of an old, closed one.
- Linking and unlinking leave an entry in the history of both issues. An entry about an issue you cannot see is left out of the history you read (`LINK-7`).
- Linking and unlinking change neither issue's version, last-updated time, or status (`LINK-5`).
- When an issue becomes resolved or closed, the assignees of the issues linked to it are told in the inbox only, without email, and only if they can see both issues (`LINK-6`).

## Trash and closing resolved issues

| Action | Customer | Agent | Admin |
|---|---|---|---|
| Delete an issue (into the trash), in any status | – | – (403) | Yes |
| See the trash, restore an issue, purge it at once | – | – (403) | Yes |

- **Nobody sees an issue in the trash**, admins included: the issue and every path under it are 404 in both apps, and it is left out of every list, search, count, and inbox (`TRASH-1`). Only the admin's trash list shows it, with when and by whom it was deleted and when it will be purged.
- **Nothing is sent about it.** While an issue is in the trash, popdesk makes no inbox item and sends no email or webhook message about it. Deleting it also deletes the messages still waiting to be sent, so restoring it does not send them later (`TRASH-2`).
- **Restoring brings it back as it was**: content, comments, attachments, tasks, links, labels, assignee, due date, and status, and its inbox items are listed again. A restore that would break the link rules is refused (409) and names the other issues.
- **popdesk purges an issue 30 days after it was deleted**, checking every hour; an admin can purge it earlier from the trash. Purging deletes what was written (title and content, comments, attachments and their files, tasks and notes, links, labels on it, inbox items) and keeps only the issue's key, company, status, times, and history entries, without the old title and content values. It cannot be undone (`TRASH-3`).
- **Issue numbers are never used again**, also after a purge (`TRASH-4`).
- Deleting, restoring, and purging by an admin leave an entry in the audit log (by company code and issue key, never the title) and in the issue's history. popdesk's own purges leave a history entry only.
- **A resolved issue closes by itself 7 days after it was resolved**, checked every 10 minutes (`STATUS-5`). popdesk closes it as "System": the reporter and the team are told as when staff close an issue. An issue reopened or moved on in the meantime is not closed. An issue in the trash is not closed; a resolved issue restored from the trash closes 7 days after the restore.

## Statistics

Staff see how many issues each customer company has: all of them, open (received or confirmed), in progress, resolved, and closed (`STATS-1`).

| Action | Customer | Agent | Admin |
|---|---|---|---|
| Issue counts per company | – | Assigned companies | All companies |

- An agent gets only the companies assigned to them. A company they are not assigned to has no line at all, so neither its name nor its counts show (`STATS-2`).
- Customers have no statistics. The path is a console path, so a customer gets the same answer as for any other console path.
- Private issues are counted. Issues in the trash and purged ones are not.
- Archived and suspended companies are listed too; archived ones are marked. A company without issues shows zeros.
- The company with the most open issues comes first, then by company code.
- While the SLA policy is on, each company also shows, for the last 7, 30, or 90 days, how many first response and resolution goals were met and missed and the median business time. An agent gets these only from the issues of their assigned companies (`STATS-4`).

## SLA

Admins set one SLA policy for the whole installation: a first response goal and a resolution goal for each priority, business hours, and holidays (`SLA-1`, `SLA-2`). Staff see where each issue stands against it (`SLA-6`).

| Action | Customer | Agent | Admin |
|---|---|---|---|
| Read and change the SLA policy | – | – (403) | Yes |
| See the SLA goals of an issue, filter and sort the issue list by them | – | Issues they can see | All issues |

- Agents get 403 for the policy, also for reading it. Customers never reach it: it is a console path, so a customer gets the same answer as for any other console path.
- **Customers never see SLA.** Portal issues and the portal issue list have no SLA fields, the portal list does not take the SLA filter or order, and no customer email or notification mentions it.
- The SLA filter and order work within the issues the caller can see: an agent never finds an issue of a company they are not assigned to through them (`RULE-03`).
- Changing the policy needs the version the admin read; when another admin saved in between, the change is refused and nothing changes. Each change that makes a new version leaves an audit entry with what changed. A version popdesk makes because `POPDESK_TIMEZONE` changed leaves none, and the policy shows "System" as who changed it.
- Only issues filed while at least one goal was set are under SLA (`SLA-8`). Changing the policy works out the due times of running clocks again within minutes; it does not change the issue's `version`, so nobody editing the issue gets a conflict from it.

When a running goal passes 80% or its due time, popdesk tells staff once in the inbox (`SLA-5`). It tells the assignee when the assignee is active and can see the issue; otherwise every active agent assigned to the company; and when there is none of them either, a warning goes to every active admin. A goal past its due time also goes to every active admin, with an email to each person told and a webhook message. Customers are never told, also when the issue's company has nobody assigned. Changing the policy does not alert each issue it moves: admins get one summary item with how many issues became overdue, due soon, or neither.

## Other areas

| Area | Customer | Agent | Admin |
|---|---|---|---|
| Notification inbox: list, unread count, mark read, mark all read | Own items, only for issues they can still see | Same | Same |
| Statistics | – | Assigned companies | All companies |
| SLA policy and business hours | – | – | Read and edit |
| SLA goals of issues | – | Issues they can see | All issues |
| Branding | Read (public, also before sign-in) | Read | Edit |
| Audit log: view, export, integrity check | – | – | Yes |
| System status: channels, failed messages, cleanup jobs; send a failed message again; send a test email to oneself | – | – | Yes |

The inbox is per account. An item of another account is 404, also for a colleague of the same company or another agent who sees the same issue. A customer's inbox holds only what a customer is told (a staff answer, the issue resolved, closed, or reopened by staff, an issue filed for them, a mention); what staff did shows as the support team, without a name. Items about an assignment, a customer's comment, SLA, an operational alert, or a membership request go to staff only (`INBOX-1` to `INBOX-3`).

## Email

Inbound email follows the same rules as the portal. Only active customer accounts are accepted. A reply becomes a comment only if the sender can see the issue and could add that comment in the portal; otherwise the sender gets an explanation and a portal link, which never names an issue the sender cannot see (`MAILIN-1`, `MAILIN-2`). A new email becomes an issue only in an active company the sender belongs to, chosen by the `+CODE` address or by the sender's only membership (`MAILIN-3`).

Outbound email links go to the portal for customers and to the console for staff. Notification emails to customers never contain internal information. Each email is checked again just before it is sent: if the issue is in the trash, the recipient is no longer active or can no longer see the issue, the comment it is about was deleted, or the recipient was taken out of the comment's mentions, it is not sent (`MAIL-4`). Only a person who gets the inbox item gets the email: not the person who did it, and nobody who could not see the issue at that moment.
