# Email

Which emails popdesk sends, to whom, and when. How to connect a mail server is in [configuration.md](configuration.md#environment-variables) (`SMTP_*` and `MAIL_FROM`) and [Mail servers](configuration.md#mail-servers); how popdesk reads a mailbox is in [Receiving email](configuration.md#receiving-email).

This page describes the code on the `main` branch. The latest release is 0.6.0. If you run an older version, read the docs at its tag, such as [`v0.4.0`](https://github.com/IMMINJU/popdesk/tree/v0.4.0/docs); a page missing there did not exist yet.

## Without a mail server

Without `SMTP_HOST`, popdesk sends nothing. It writes one log line in place of each email, with the address masked after its first character. An admin who invites someone sees the invitation link on the screen and passes it on, and an admin's password reset for someone shows the reset link once.

## The emails

| Email | To | When |
|---|---|---|
| Invitation | The invited person | An admin invites staff or a customer, a customer manager invites a new customer, or an invitation is sent again. The link works for 7 days and opens the person's app |
| Password reset | The account owner | The person asks for it on the sign-in page, or an admin resets their password. At most one every 15 minutes per account; the link works for 1 hour |
| Support team answered | The reporter | Staff add a public comment. Internal comments send nothing |
| Issue filed for you | The reporter | Staff file an issue on behalf of a customer |
| Issue resolved, closed, or reopened | The reporter | Staff resolve, close, or reopen the issue, or popdesk closes a resolved issue after 7 days |
| You were mentioned | Each account the comment mentions | Staff mention someone in a comment. In a public comment that can be the reporter. Editing a comment tells only the accounts new to its mentions |
| You are the assignee | The new assignee | Someone sets or changes the assignee of an issue |
| Task assigned | The new assignee of the task | A task is created with an assignee or gets a new one, unless the assignee did it |
| SLA first response or resolution overdue | The assignee, or the company's agents, and every active admin | A goal of the issue passes its due time ([SLA](../use/permissions.md#sla)) |
| Membership request | Every active admin | A customer manager invites an email that already has an account |
| Email not turned into an issue | The sender of a received email | The email named no company the sender belongs to, or the issue key in its subject is one the sender cannot see. It names no issue and no company |
| Reply not added | The sender of a received email | The reply was for a closed issue, or for one the sender may not comment on. It holds the issue's link |
| Test email | The admin who sends it | An admin sends it from the system status |

popdesk sends no email for a customer's comment, a reporter closing or reopening their own issue, a change to a linked issue, an SLA goal that passes 80%, or an operational alert. Those go to the inbox in the console only.

## Who gets an email and who does not

- Only a person who gets the inbox item gets the email. The person who did it is not told, and neither is an account that is not active or cannot see the issue at that moment.
- Just before it sends, popdesk checks again. An email is dropped when the issue is in the trash, the recipient is no longer active or can no longer see the issue, the comment was deleted, or the recipient was taken out of the comment's mentions ([permissions.md](../use/permissions.md#email)).
- Links go to the portal for customers and to the console for staff.
- Emails to customers name staff only as the support team and hold no internal information. No email holds the text of a comment or of an issue's content; an email about an issue gives its key, its title, and a link.
- Each email is plain text, in the recipient's language. `POPDESK_LOCALE` is the default language. The sender's name is the branding's display name, unless `MAIL_FROM` has a name of its own.

## Sending, retries, and limits

Emails wait in a queue in the database and are sent in the background. A failed email is tried again for 24 hours, and admins see the queue and failed emails in the console's system status, where they can send a failed email again and send a test email. At most 50 emails an hour go to one address; the 51st within the hour is failed at once, with the error kind `RATE_LIMITED`, and is not tried again by itself. All of this is in [Sending email and webhook messages](configuration.md#sending-email-and-webhook-messages).

## Replies

While receiving email is on, an email to a customer about an issue has `Reply-To` set to `MAIL_INBOUND_ADDRESS`, and the customer's reply becomes a comment on the issue. Emails have `Auto-Submitted: auto-generated`, except the two answers to a received email, which have `Auto-Submitted: auto-replied` and no `Reply-To`. See [Receiving email](configuration.md#receiving-email).

## A sandbox

With `POPDESK_SANDBOX=true`, every email goes to `POPDESK_SANDBOX_MAIL_TO` instead of its recipient, or nowhere without it ([A sandbox](configuration.md#a-sandbox)).
