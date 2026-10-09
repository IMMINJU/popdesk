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
