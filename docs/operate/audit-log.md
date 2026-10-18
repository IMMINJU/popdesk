# Audit log

How long audit entries are kept, what the integrity check can and cannot tell you, and how to keep a copy of the log outside popdesk. The settings are in [configuration.md](configuration.md); the formats are in [the API conventions](../integrate/conventions.md#audit-log-hash-chain).

popdesk is not released yet. Values here are the planned contract and may change before the first release.

## How long entries are kept

Entries older than `POPDESK_AUDIT_RETENTION` (default `P365D`) are deleted once a day. The age of an entry is the difference between now and its time, both in UTC; a day is 24 hours.

- The value is an ISO-8601 duration of days and time, such as `P365D`, `P90D`, or `PT720H`. Years and months (`P1Y`, `P6M`) are not accepted, because their length is not fixed.
- The shortest period is `P30D`. With a shorter value, or one that is not a duration, popdesk does not serve requests and deletes nothing: the process keeps running, logs the name of the setting, and its health check stays unhealthy.
- Entries are deleted only from the start of the log, as far as old entries follow one another. An entry gets its time when the action is done and its place in the log when the change is committed, so now and then an entry sits right behind one that is a moment newer. Such an entry stays until the one in front of it is old enough too, at most a day longer. Deleting it out of the middle would break the hash chain.
- The job is `audit-retention` in the cleanup metrics (`popdesk_cleanup_last_run_seconds`, `popdesk_cleanup_deleted`, label `job`).
- The first run comes right after the first start on a version that has this job, so an upgrade deletes the entries that are already older than the period at once.
- A shorter period takes effect on the next daily run after the restart and cannot be undone. Export the entries first if you may need them ([export format](../integrate/conventions.md#audit-log-export)).

## What the integrity check tells you

Every entry holds a hash over its values and the entry before it. The integrity check in the console (audit log, admins only) computes every hash again from the entries in the database.

It finds:

- an entry whose values were changed,
- an entry that was deleted from the middle,
- entries that were deleted from the end.

It does not find a log that someone rewrote from start to end. A person who can write to the database can change an entry and then compute every hash after it again; the check then says the log is whole. So "valid" means "nobody changed a part of it", not "nobody could have changed it".

To cover that case, keep the hashes somewhere the database administrator cannot write:

- turn on the outside copy (below), so every entry leaves popdesk with its hash as it is written, or
- export the log regularly and keep the files, or at least the `hash` of the last line, elsewhere.

An entry in an outside copy or an old export can then be compared with the same entry in popdesk: same `id`, same `hash`. To check an exported file without popdesk, run [`audit-verify.py`](../integrate/audit-verify.py) on it.

### After an anonymization

Anonymizing an account ([privacy.md](privacy.md)) is the one time popdesk itself changes entries. It puts the anonymous name and email into that person's entries and computes the hash of every entry from the first changed one to the end of the log again, all in one transaction. It writes an `AUDIT_RECHAINED` entry with the range (`fromSeq`, `toSeq`) and the last hash before and after (`headHash`), right before the `ACCOUNT_ANONYMIZED` entry.

- The integrity check in popdesk says the log is whole afterwards. If popdesk stops halfway, the whole anonymization is undone and the log stays as it was.
- An outside copy or an export from before now differs from popdesk in that range: other values and other hashes. That is not a sign of tampering. `audit-verify.py` reads the `AUDIT_RECHAINED` entries and reports such a range as "computed again", while it still checks every hash against its own values, and the `AUDIT_RECHAINED` entry against the new last hash. A mismatch outside such a range is still reported as a break.
- Compare the `headHash.from` of an `AUDIT_RECHAINED` entry with the last hash you kept elsewhere: if they match, the log up to that point was whole before the anonymization.
- One anonymization runs at a time. The daily removal of old entries waits until it is done. Sign-ins and other changes go on while it runs: the end of the log is held only while popdesk joins the entries that came in meanwhile, not for the whole computation. On a developer machine, 100,000 entries took about 10 seconds. The request stays open until the computation is done, so the proxy limit of [the check](#how-long-the-check-and-an-export-take) applies to it too.

### How long the check and an export take

Measured on a development machine with 1,000,000 entries: the integrity check took 12 seconds, and a JSON Lines export 11 seconds (395 MB). Both grow with the number of entries; 5,000,000 entries are about a minute.

Both are one HTTP request that stays open until it is done. A reverse proxy in front of popdesk usually ends a request after 60 seconds (`proxy_read_timeout` in nginx). If your log is large, raise that limit for the console host, or the check ends with a connection error although popdesk finishes it. One check runs at a time, and two exports.

## A copy outside popdesk

With `AUDIT_FORWARD_URL` set, popdesk posts every audit entry to that address as it is written, for example to a log collector or a SIEM. The body and the delivery rules are in [the API conventions](../integrate/conventions.md#audit-log-outside-copy).

- **Only new entries are sent.** Entries written before you set the address are not sent. Move those with an export.
- **Authentication.** Set `AUDIT_FORWARD_TOKEN` and popdesk sends `Authorization: Bearer <token>` with every entry. Without it, no `Authorization` header is sent. Do not put a token or a password into the address: an address ends up in proxy logs. The address and the token are never written to popdesk's log, API answers, or metrics. Both can be given as a file (`AUDIT_FORWARD_URL_FILE`, `AUDIT_FORWARD_TOKEN_FILE`).
- **Use `https`.** With an `http` address popdesk logs a warning at startup, and a second one when a token is set, because the token then crosses the network unencrypted. Redirects are not followed, so the token goes only to the host you set.
- **The copy holds personal data.** Each entry carries the name and email address of the person who acted, and often of the account it was done to. The receiver stores them under its own rules: deleting or anonymizing an account in popdesk later does not change what was already sent. Choose a receiver you may send that data to.
- **After an anonymization.** Copies still waiting go out with the anonymous values and the new hashes. Copies already sent are not sent again; the receiver gets the `AUDIT_RECHAINED` entry, which names the range whose hashes changed. Remove the old values at the receiver yourself if you must ([privacy.md](privacy.md)).
- **When the receiver is down.** popdesk tries again for 24 hours (after 1 second, then 2, 4, and so on, at most every 10 minutes). After that the entry's copy is given up: it shows as failed in the console's system status with the entry's ID, every admin gets an item in the console inbox, and the row is deleted 7 days later. Until then an admin can send it again from the system status. For copies that are gone, export the entries of that time and load them into the receiver; they have the same fields. When the copy has only failed for over an hour, every admin gets an `AUDIT_FORWARD_STALLED` item in the console inbox.
- **Watching it.** The system status shows the copy as a channel of its own: on or off, the last successful send, and the queued and failed copies. The metrics `popdesk_outbox_queued`, `popdesk_outbox_failed`, and `popdesk_outbox_sent_total` have the label `channel="audit_forward"`.
- **Commands.** `popdesk backup`, `popdesk restore`, and `popdesk admin reset-password` also write audit entries. They read the same environment as the app, so run them in the app's container (`docker compose exec app …`, `docker compose run app …`) as [configuration.md](configuration.md#commands) shows; the app then sends their copies.

### A test installation from a copy of the real database

A copy of your database started with your real settings would post its own audit entries to your real receiver and mix them with the real ones. Start such an installation with `POPDESK_SANDBOX=true`: the outside copy is then off although `AUDIT_FORWARD_URL` is set. Nothing is queued, copies that were waiting in the copied database are dropped without being sent, and the system status shows the copy as off.

Check in the system status that the copy is off before you use the test installation. A sandbox also turns off the webhook and sends email to one address only, or to none ([configuration.md](configuration.md#a-sandbox)).

The same happens on any start with the copy off, also on your real installation: when you remove `AUDIT_FORWARD_URL` or turn the sandbox on, the copies that were waiting at that moment are dropped and not sent later. Export the entries of that time if the receiver must have them.

## Sending again and test emails in the log

Two kinds of entry record what an admin did in the system status. Neither has a target; what happened is in the changes.

| Kind | Written when | Changes |
|---|---|---|
| `DELIVERY_RESENT` | An admin sent a failed message again and it went back into the queue. A request that changed nothing (no such message, a channel that is off, a message that is in the queue already) writes no entry | `channel` (`MAIL`, `WEBHOOK`, or `AUDIT_FORWARD`) and `kind`, the kind of the message, for example `STAFF_COMMENT` |
| `TEST_MAIL_SENT` | An admin sent a test email, whether the SMTP server took it or not. A refused request (email is off, too many test emails) writes no entry | `ok` (`true` or `false`), and `errorKind` when it failed, for example `SMTP_REFUSED` |

An entry never holds the address a message went to, an issue's title, the webhook address, or what the SMTP server answered.
