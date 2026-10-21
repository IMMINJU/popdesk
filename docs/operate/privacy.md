# Personal data

What personal data popdesk keeps and for how long, how to answer a person who asks for their data or for its deletion, what an anonymization changes and what it leaves, how to export one company's data, and how closed issues can be cleaned up on their own. The API is in [the API conventions](../integrate/conventions.md#account-export-and-anonymization-pii-3-pii-4) and its [company export](../integrate/conventions.md#company-export-pii-8).

This page describes the code on the `main` branch. The latest release is 0.5.0. If you run an older version, read the docs at its tag, such as [`v0.4.0`](https://github.com/IMMINJU/popdesk/tree/v0.4.0/docs); a page missing there did not exist yet.

## What popdesk keeps

| Where | What |
|---|---|
| Accounts | Email, name, language, role, status, the password hash, failed sign-ins and a sign-in lock, when the account was made |
| Profile photos | One PNG per account that has one, made by popdesk from the uploaded file (table `account_photo`) |
| Sessions, invitations, password resets | The session of each sign-in, and the tokens of open invitations and password resets |
| OIDC links | For an account that signed in with the OIDC provider, the provider's issuer and the person's `sub` there, an ID the provider gives (table `oidc_link`). An OIDC sign-in under way keeps its random values for 10 minutes, as hashes, with no person (table `oidc_request`) |
| Issues, comments, attachments | What customers and staff write and upload: titles, content, files, and who did it. popdesk cannot choose what people put into them |
| Membership requests | A copy of the requesting customer's email and name |
| Email queue and inbox | Which account a queued email or an inbox item is for, and what it is about. The address is read when the email is sent |
| Audit log | The person who acted (account ID, name, email) and the values that changed, often the name and email of the account it was done to |
| Received email | A record of each received email: a hash of its sender and `Message-ID` (or of the whole email), what became of it, and, for email turned into an issue, a comment, or a notice, or quarantined for going over the rate limit, the sender's account and address; anonymizing the account clears that address. A discarded email (unknown sender, automated, failed DMARC) keeps no address. The mailbox keeps the email itself in the processed and quarantine folders |
| Company exports | The ZIP files of [company exports](#exporting-a-companys-data), in `POPDESK_EXPORT_DIR`, for 24 hours |

popdesk does not keep IP addresses: sign-in limits are counted in memory only. Its log masks every email address after the first character and holds no password, token, invitation link, or issue content.

## How long popdesk keeps it

| Data | Kept |
|---|---|
| Issues, comments, attachments, tasks | Until they are deleted. A deleted issue stays in the trash for 30 days and is then purged. Optionally, `POPDESK_ISSUE_RETENTION` moves issues closed longer than that to the trash ([below](#automatic-issue-cleanup)) |
| Accounts | Until they are anonymized |
| Audit log | `POPDESK_AUDIT_RETENTION`, 365 days by default, at least 30 ([audit-log.md](audit-log.md)) |
| Inbox items | Until their issue is purged or their account is anonymized |
| Emails and webhook messages that failed | 7 days |
| Images uploaded for content and not used | 24 hours |
| Sessions | 8 hours after the sign-in |
| Records of received email (hash, outcome, sender address) | 90 days, also while receiving email is off |
| Received email in the processed and quarantine folders of the mailbox | 90 days from the day the mail server received it; popdesk deletes it once a day while receiving email is on. On a mail server without `UIDPLUS`, popdesk does not delete it and logs a warning once a day; delete it in the mailbox yourself. Mail left in the incoming folder is not deleted |
| `Message-ID` of sent notification email | 90 days; a reply after that is found by the `[issue key]` in its subject only |
| Password reset tokens | Until used or expired (1 hour) |
| `Idempotency-Key` of a filing | 24 hours |
| Company export files | 24 hours. A customer anonymized meanwhile keeps the old values in a file made before |
| App log (standard output) | popdesk keeps none; the log collector of the installation decides |

Copies outside the database hold the same data: backups (`popdesk backup`), files in `disk` or `s3` storage, audit log exports, the [outside copy of the audit log](audit-log.md#a-copy-outside-popdesk), and emails already sent.

## Answering a request for deletion

An admin does this in the console, member list, row menu of the person:

1. **Export, if the person wants their data.** The export downloads one JSON file: the account, the profile photo, the issues they reported, and the comments and attachment names they wrote. It holds nobody else's name, email, or account ID. Send it to the person by a channel you trust; popdesk keeps no copy.
2. **Anonymize the account.** The anonymization asks for the person's email to be typed in, then makes the change at once. It cannot be undone.
3. **Delete what must go.** The issues the person reported stay after an anonymization. Move the ones that must go to the trash and purge them there (admins, trash). Attachments and comments go with their issue.

Other people's issues and comments may name the person in their text. popdesk does not find those: search for the person's name and email in the issue list and change or delete what you find.

### When an anonymization is refused because the audit log is broken

The anonymization checks the audit log's hash chain from the person's first entry to the end before it changes anything. If an entry there was changed or removed in the database, it is refused with `AUDIT_CHAIN_BROKEN`, nothing changes, and the deletion request stays open until someone decides how to go on. popdesk does not anonymize over a break, because the new hashes would hide it.

1. **Run the integrity check** (admins, audit log). It names the first entry that does not hold, the same place the refusal gave as `brokenSeq`. A break was there before the anonymization; the check would have failed on it anyway.
2. **Keep the evidence.** Before anything changes, export the audit log and take a backup (`popdesk backup`), and keep the outside copy if you have one. Find out who could write to the database, and when.
3. **Decide how to restore the log.** Compare the entries with the outside copy or an older export, which hold the values popdesk wrote. Put the changed values back from there, or restore a backup from before the change, then run the check again. When the check says the log is whole, anonymize the account again.

Tell the person that the deletion is delayed and why, as the law that applies to you requires.

## Exporting a company's data

When a customer company leaves, or asks for what it gave you, an admin exports the company in the console: company detail, section "Export".

1. **Choose what goes in.** Every export has the company's issues, their public comments, the files of both, and the company's member customers (email and name). Two choices add more, both off by default: private issues (other customers of the company cannot see them, so the person you send the ZIP to would see what their colleagues could not), and internal comments and tasks with their files. Staff are always "support team" in the ZIP, also in internal comments and tasks, but a name written inside the text of a comment goes out as written: read the internal comments before you choose them.
2. **Wait for the ZIP.** popdesk writes it in the background. One export runs at a time in the installation. The list shows it as running; when it is done, the admin who asked gets an inbox item, and when it fails every admin does, with the reason in the list (`STORAGE`: the attachment storage could not be read; `DISK`: the ZIP could not be written; `INTERRUPTED`: the app stopped meanwhile). A file the storage no longer has is left out and named in `manifest.json`.
3. **Download it within 24 hours.** Any admin can download it. Each download is recorded in the audit log (`COMPANY_EXPORT_DOWNLOADED`), as the export is (`COMPANY_EXPORTED`). After 24 hours popdesk deletes the file; the list keeps the export as expired. Send the ZIP by a channel you trust.

What the ZIP holds is listed in [the API conventions](../integrate/conventions.md#company-export-pii-8). It holds nothing of other companies: not their issues, not links to them, not customers who belong only to them. The ZIP is written to the app's disk ([configuration.md](configuration.md#company-exports)): give that folder room for the company's attachments.

## Automatic issue cleanup

Off by default. With `POPDESK_ISSUE_RETENTION` set, popdesk moves to the trash, once a day, every issue that is `CLOSED` and was closed longer ago than that; 30 days later the trash purges it, as it purges any. It is a way to keep issues no longer than you need them.

- **Turning it on.** Set an ISO-8601 period of days or hours, at least `P90D` (for example `P1095D`, three years) and start the app again. A value of years or months (`P3Y`), any other text, or a period shorter than `P90D` stops the app at its start, with the key and the reason in its log.
- **The first day.** When the cleanup is turned on, or its period is made shorter, it moves nothing for 24 hours. Every admin gets an inbox item with the number of issues it would move and the period in days. That is the time to fix a wrong value: set it back and start the app again, and the shorter period moves nothing. A longer period, or the same one written otherwise (`P90D` and `PT2160H`), starts no wait.
- **What is moved.** An issue whose period counts from when it was closed or, if later, when it was last restored from the trash. An issue reopened, open, or already in the trash is not moved; an issue an admin deletes while the cleanup comes for it ends deleted by the admin.
- **How it shows.** The trash lists a moved issue with "automatic cleanup" as who deleted it, and its history says popdesk moved it. No audit entry is written; the cleanup record (system status, `issue-retention`) has the time and the count of each run.
- **Undoing it.** Restore the issue from the trash within 30 days. It comes back as it was, and the cleanup counts its period again from the restore.
- **Turning it off.** Remove the key and start the app again. The next time it is turned on is a first day again.

## What an anonymization changes

- The name becomes `Deleted user` or `삭제된 사용자`, in the installation's language at that moment (`POPDESK_LOCALE`), and the email `deleted-{id}@invalid`. The account becomes `SUSPENDED` and is marked as anonymized in the member list.
- Removed in the same step: every session (the person is signed out at once), the profile photo, the password, a sign-in lock, open invitation and password reset tokens, the OIDC link (so a person invited again with the same email does not reach the old account by OIDC), emails queued to the person, the person's inbox items, and the sender address in the records of the email the person sent.
- The copies of the name and email in the person's membership requests and audit entries get the anonymous values. The audit log's hash chain is computed again from the first changed entry and the change is recorded ([audit-log.md](audit-log.md#after-an-anonymization)).
- Optionally, the person is taken off as assignee of every open issue and task, as for a suspension.
- Nothing brings the account back: lifting the suspension, renaming it, changing its role, resetting its password, sending its invitation again, adding it to a company, and approving its membership request are refused.
- The old email is free: inviting it again makes a new account that has nothing of the old one.

## What an anonymization leaves

- What the person wrote: issues, comments, attachments, task notes. They stay and show the anonymous name as their author. Customers still see staff as the support team.
- Other people's text that names the person (see step 3 above).
- An outside copy of the audit log that was being sent at the moment of the anonymization can go out with the old values: popdesk reads the entry when it sends it. Treat it like a copy already sent.
- Company export files made before the anonymization, for at most 24 hours: they keep the person's old name and email as a member customer and as an author. Wait for them to expire, or delete them in `POPDESK_EXPORT_DIR`.
- Email the person sent to the inbound address, in the processed and quarantine folders of the mailbox: it keeps the old address until popdesk deletes it 90 days after it came in. Delete it in the mailbox sooner if you must.
- Data outside the database: audit entries already sent to the outside copy, audit log exports, account exports, and company exports already downloaded, emails already sent, and backups taken before. They keep the old name and email. Remove them under your own rules, or keep backups only as long as you need them.
