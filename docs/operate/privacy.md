# Personal data

What personal data popdesk keeps, how to answer a person who asks for their data or for its deletion, and what an anonymization changes and what it leaves. The API is in [the API conventions](../integrate/conventions.md#account-export-and-anonymization-pii-3-pii-4).

popdesk is not released yet. Values here are the planned contract and may change before the first release.

## What popdesk keeps

| Where | What |
|---|---|
| Accounts | Email, name, language, role, status, the password hash, failed sign-ins and a sign-in lock, when the account was made |
| Profile photos | One PNG per account that has one, made by popdesk from the uploaded file (table `account_photo`) |
| Sessions, invitations, password resets | The session of each sign-in, and the tokens of open invitations and password resets |
| Issues, comments, attachments | What customers and staff write and upload: titles, content, files, and who did it. popdesk cannot choose what people put into them |
| Membership requests | A copy of the requesting customer's email and name |
| Email queue and inbox | Which account a queued email or an inbox item is for, and what it is about. The address is read when the email is sent |
| Audit log | The person who acted (account ID, name, email) and the values that changed, often the name and email of the account it was done to |

popdesk does not keep IP addresses: sign-in limits are counted in memory only. Its log masks every email address after the first character and holds no password, token, invitation link, or issue content.

Copies outside the database hold the same data: backups (`popdesk backup`), files in `disk` or `s3` storage, audit log exports, the [outside copy of the audit log](audit-log.md#a-copy-outside-popdesk), and emails already sent.

## Answering a request for deletion

An admin does this in the console, member list (C-10), row menu of the person:

1. **Export, if the person wants their data.** The export downloads one JSON file: the account, the profile photo, the issues they reported, and the comments and attachment names they wrote. It holds nobody else's name, email, or account ID. Send it to the person by a channel you trust; popdesk keeps no copy.
2. **Anonymize the account.** The anonymization asks for the person's email to be typed in, then makes the change at once. It cannot be undone.
3. **Delete what must go.** The issues the person reported stay after an anonymization. Move the ones that must go to the trash and purge them there (admins, trash). Attachments and comments go with their issue.

Other people's issues and comments may name the person in their text. popdesk does not find those: search for the person's name and email in the issue list and change or delete what you find.

## What an anonymization changes

- The name becomes `Deleted user` or `삭제된 사용자`, in the installation's language at that moment (`POPDESK_LOCALE`), and the email `deleted-{id}@invalid`. The account becomes `SUSPENDED` and is marked as anonymized in the member list.
- Removed in the same step: every session (the person is signed out at once), the profile photo, the password, a sign-in lock, open invitation and password reset tokens, emails queued to the person, and the person's inbox items.
- The copies of the name and email in the person's membership requests and audit entries get the anonymous values. The audit log's hash chain is computed again from the first changed entry and the change is recorded ([audit-log.md](audit-log.md#after-an-anonymization)).
- Optionally, the person is taken off as assignee of every open issue and task, as for a suspension.
- Nothing brings the account back: lifting the suspension, renaming it, changing its role, resetting its password, sending its invitation again, adding it to a company, and approving its membership request are refused.
- The old email is free: inviting it again makes a new account that has nothing of the old one.

## What an anonymization leaves

- What the person wrote: issues, comments, attachments, task notes. They stay and show the anonymous name as their author. Customers still see staff as the support team.
- Other people's text that names the person (see step 3 above).
- Data outside the database: audit entries already sent to the outside copy, audit log exports and account exports already downloaded, emails already sent, and backups taken before. They keep the old name and email. Remove them under your own rules, or keep backups only as long as you need them.
