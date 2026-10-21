# Backup and restore

Where to find how to back up popdesk, restore it, and check the attachment files afterwards. The steps themselves are in [configuration.md](configuration.md), so they are kept in one place.

This page describes the code on the `main` branch. The latest release is 0.5.0. If you run an older version, read the docs at its tag, such as [`v0.4.0`](https://github.com/IMMINJU/popdesk/tree/v0.4.0/docs); a page missing there did not exist yet.

## What a backup holds

`popdesk backup` writes a dump of the database while the app runs. Where the attachment files are depends on `POPDESK_STORAGE`:

| `POPDESK_STORAGE` | The dump holds the files | What else to back up |
|---|---|---|
| `db` (the default) | Yes | Nothing |
| `disk` | No | The folder (`POPDESK_STORAGE_DIR`), after the dump |
| `s3` | No | The bucket, after the dump |

If the storage was ever `disk` or `s3`, old files stay there, so keep backing up that place for as long as attachments are stored in it. Back up the marker file `.popdesk-storage` with the files: popdesk does not start when it is missing. Profile photos and branding logos are in the database, so the dump covers them whatever the storage is.

Keep the dump somewhere other than the Docker host. Backups hold personal data; see [privacy.md](privacy.md#how-long-popdesk-keeps-it).

## The steps

- [Back up](configuration.md#back-up): `docker compose exec app popdesk backup /tmp/popdesk.dump` writes the dump inside the app container; then copy it out with `docker compose cp` and delete it in the container.
- [Restore](configuration.md#restore): into a new, empty database, with the app stopped. Restore the folder or the bucket as well before you start the app.
- [Check the attachment files](configuration.md#check-the-attachment-files): once the restored app runs again, `docker compose exec app popdesk attachments check` lists every file that is missing or has another size. It only reports; a missing file has to come back from your file backup.
- [Attachment storage](configuration.md#attachment-storage): the marker, the volume, and the start check.
- [Commands](configuration.md#commands): the `popdesk` command and what each subcommand does.

## Before an upgrade

Back up the database, and then the attachment folder or bucket, before you install a new version. Migrations only move forward: to go back to an older version, you restore that backup. See [upgrade.md](upgrade.md#before-you-upgrade).
