# Upgrading

How popdesk changes its database when you install a new version, and what to do when that fails.

popdesk is not released yet. These steps are the plan for the first release and may change.

## Before you upgrade

Back up the database first (`popdesk backup <file>`, see [configuration.md](configuration.md#commands)). When attachments are stored on `disk` or in `s3`, back them up after the database. Every release note repeats this.

## What happens on start

On each start popdesk:

1. takes the single-instance lock, so a second copy of the app cannot migrate at the same time;
2. checks that its database user may create tables in its schema;
3. refuses to start if the database has a migration this version does not know (the schema is newer than the app) or a migration that failed earlier;
4. runs the new migrations, in order;
5. creates the first admin if the database has none, and then opens its ports.

While migrations run, the ports are closed and health checks cannot connect. Give the health check enough time (see [configuration.md](configuration.md#health-checks-while-starting)).

Migrations only move forward. There are no down migrations: to go back to an older version, restore the backup you took before the upgrade.

## Upgrading to 0.2.0

0.2.0 joins the audit log into a hash chain. On its first start it gives every audit entry that is already there its place in the chain and its hash (migration 7), in one transaction, before the ports open.

- **How long it takes.** Measured on a development machine: 2.6 seconds for 100,000 audit entries, 26 seconds for 1,000,000. It grows with the number of entries. Each sign-in is an audit entry, so an installation in use for a while can have many; count them before you upgrade with `SELECT count(*) FROM audit_log;`.
- **The ports stay closed meanwhile.** Health checks cannot connect until the migration is done. The Compose file's `start_period` of 3 minutes is enough for some millions of entries on such a machine; with more, or on a slow disk, raise `start_period` in your copy of the Compose file (on Kubernetes, the `failureThreshold` of the `startupProbe`) before you upgrade, as [configuration.md](configuration.md#health-checks-while-starting) shows. If the app is stopped before the migration is done, nothing is kept: the next start computes everything again from the beginning.
- **There is no way back to the old image.** After this migration the 0.1.0 image does not start on the database (see [below](#the-database-is-newer-than-the-app)). To go back, restore the backup you took before the upgrade; what was written after it is lost.
- Entries written before the upgrade are checked by the integrity check like any other from then on. The hash covers what the database held at the time of the upgrade; it says nothing about changes made before.

## Upgrading to 0.3.0

0.3.0 adds customer managers (migration 8): a manager mark and a row ID on every membership of a customer in a company, a table for membership requests, and the company an inbox item is about.

- **It does not take long.** The migration adds columns and a table and computes nothing. Every membership that is there gets a random row ID from the column default; no customer is a manager after the upgrade.
- **There is no way back to the old image.** After this migration the 0.2.0 image does not start on the database (see [below](#the-database-is-newer-than-the-app)). To go back, restore the backup you took before the upgrade; what was written after it is lost.
- Nothing has to be set. An admin marks managers on the console afterwards.

It also adds the search of issues, their assignee and history, and the keys that keep an issue from being filed twice (migrations 9 to 11). Migration 9 creates the `pg_trgm` extension, new columns and tables, and one history entry ("created") for every issue that is there. Migration 10 reads every issue and stores the text the search looks in: the title and the content without its Markdown marks. Migration 11 builds the search index.

- **The database needs the `pg_trgm` extension.** With the Compose file's database nothing has to be done. With your own or a managed PostgreSQL, the app's database user must be allowed to create the extension, or an administrator creates it before the upgrade (`CREATE EXTENSION pg_trgm;` in popdesk's database); see [configuration.md](configuration.md#a-database-you-run-yourself). When the extension is missing and cannot be created, popdesk does not start and logs this line; nothing of migration 9 is kept, so create the extension and start again:

  ```
  Database migration 9 failed, so popdesk did not start: the database popdesk has no pg_trgm extension, which the issue search needs, and the database user popdesk could not create it. Have a database administrator run CREATE EXTENSION pg_trgm; in the database popdesk, then start popdesk again. Nothing of this migration was kept.
  ```

- **How long it takes.** Measured on a development machine with 100,000 issues of about 2,000 characters each: migration 9 took 1.5 seconds, migration 10 took 13 seconds, and migration 11 took 51 seconds, 78 seconds from start to healthy. It grows with the number of issues and the length of their content; count them with `SELECT count(*) FROM issue;`. The ports stay closed meanwhile, as in the 0.2.0 upgrade above: with some hundreds of thousands of issues, or a slow disk, raise the health check's `start_period` before you upgrade. Each of the three migrations runs in its own transaction. If the app is stopped in between, the next start goes on with the first one that is not done.
- **The database grows.** The search text is a second copy of every issue's text, and the index comes on top. In the measurement above the issue table with its indexes went from 279 MB to 722 MB (191 MB of it the search index), and the whole database from 289 MB to 748 MB. Make sure the database's disk has room for about twice the size of the issue table more before you upgrade. Check it with `SELECT pg_size_pretty(pg_total_relation_size('issue'));`.
- **There is no way back to the old image**, as above.
- **Searching.** A search text of three or more characters uses the index: in the measurement, a text that 10 of the 100,000 issues hold took 1 millisecond in the database, and one that 20,000 hold took 0.55 seconds. A text of one or two characters cannot use the index and reads every issue, about 2 seconds there. An account can send 60 searches a minute.
- A dump taken with `popdesk backup` from this version on names the extension. `popdesk restore` restores it into an empty database when the app's database user may create the extension, or when the extension is there already.

## Upgrading to the version after 0.3.0

The next version adds editing and deleting comments, mentions, and the notification inbox of both apps (migration 12): three columns on every comment (when it was edited, when and by whom it was deleted), a table for the accounts a comment mentions, three columns on every inbox item (the issue, the comment, and who did it), and the company of a membership request email in the send queue.

- **It does not take long.** The migration adds columns, one table, and three indexes on the inbox table, and computes nothing. Rows that are there are not changed: after the upgrade every comment has never been edited, is not deleted, and mentions nobody, and the inbox items of admins are listed as before, about no issue.
- **There is no way back to the old image.** After this migration the 0.3.0 image does not start on the database (see [below](#the-database-is-newer-than-the-app)). To go back, restore the backup you took before the upgrade; what was written after it is lost.
- Nothing has to be set. Customers get an inbox on the portal, and both apps show the unread count.
- **More email goes out.** With `SMTP_HOST` set, popdesk now also emails the reporter when staff resolve, close, or reopen an issue, a person who is mentioned in a comment, the new assignee of an issue, and every active admin when a customer manager asks for an existing account to join a company. Without `SMTP_HOST` each of these is one log line, as for the emails before.
- **`POPDESK_SANDBOX=true` now holds back email and the webhook.** Until 0.3.0 it turned off the audit copy only. From this version on a sandbox also posts no webhook, and sends every email to `POPDESK_SANDBOX_MAIL_TO`, or no email at all when that is not set ([configuration.md](configuration.md#a-sandbox)). Webhook messages waiting in the queue of a sandbox are dropped. If an installation of yours runs with `POPDESK_SANDBOX=true` and should send real email, remove the setting before you upgrade.
- **`POPDESK_SANDBOX_MAIL_TO` is read now**, in a sandbox only. With a value that is not one email address a sandbox does not start; an empty value is as none.
- **At most 50 emails an hour go to one address.** An email over that is failed at once with the error kind `RATE_LIMITED`. New metric: `popdesk_mail_rate_limited_total`.
- **New in the system status**: sending a failed message again, a test email, the sandbox, and the cleanup jobs. The two new API operations answer 400 with the new error code `CHANNEL_DISABLED` when the channel is off.
- **New inbox items for admins**: `MAIL_STALLED`, `WEBHOOK_STALLED`, and `AUDIT_FORWARD_STALLED`, when a channel has only failed for over an hour. An installation whose queue holds only messages that were given up gets none.
- **New kinds of audit entry**: `DELIVERY_RESENT` and `TEST_MAIL_SENT` ([audit-log.md](audit-log.md#sending-again-and-test-emails-in-the-log)). A receiver of the audit copy or of exports that lists the kinds it knows needs these two.

## A migration failed

popdesk stops and logs a line like this:

```
Database migration 7 failed, so popdesk did not start. Follow the recovery steps in docs/operate/upgrade.md before starting again.
```

The number is the migration version. popdesk does not start again until the problem is solved; every later start logs the same version.

What the database looks like depends on the kind of migration:

- **Most migrations run in a transaction.** PostgreSQL rolls back the whole migration, so the database is as it was before. The migration history (`flyway_schema_history`) has no row for it.
- **A migration that cannot run in a transaction** (for example `CREATE INDEX CONCURRENTLY`) may leave part of its work behind, such as an index marked invalid, and the migration history has a row for it with `success = false`.

To recover:

1. Read the app log around the failed line to see why it failed (for example a lock that timed out, a disk that is full, or data the migration did not expect).
2. Choose one of these:
   - **Restore the backup** you took before the upgrade, keep running the previous version, and report the failure. This always works and is the safest choice.
   - **Fix the cause and start again.** If the failed migration ran in a transaction, nothing else is needed: start the same version again once the cause is fixed. If it left a row with `success = false`, first remove what it left behind (for example `DROP INDEX CONCURRENTLY IF EXISTS <name>` for an invalid index), then delete its row from the history:

     ```sql
     DELETE FROM flyway_schema_history WHERE version = '<version>' AND NOT success;
     ```

     Then start popdesk again; it runs the migration again.
3. If you are not sure what the migration left behind, restore the backup.

Do not edit the migration files in the image, and do not mark a failed migration as successful by hand: later versions expect everything it does to be there.

## The database is newer than the app

If you start an older image on a database that a newer version already migrated, popdesk does not start and logs the version it found:

```
The database has migration 12, newer than this popdesk version knows (latest 9). popdesk does not start on a newer schema: restore the backup you took before the upgrade, or run the newer version.
```

Either run the newer version again, or restore the backup taken before the upgrade and then start the older version.

## Another instance holds the lock

popdesk runs as one instance (ADR D-23). A starting instance waits `POPDESK_INSTANCE_LOCK_WAIT` (default 30 seconds) for the lock and then stops with a message that another instance is running. Stop the other instance, or wait until it has stopped, and start again. On Kubernetes, use the `Recreate` deployment strategy so the old pod stops before the new one starts.
