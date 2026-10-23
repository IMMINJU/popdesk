# Backup and restore

Where to find how to back up popdesk, restore it, and check the attachment files afterwards. The steps themselves are in [configuration.md](configuration.md), so they are kept in one place.

This page describes the code on the `main` branch. The latest release is 0.6.0. If you run an older version, read the docs at its tag, such as [`v0.4.0`](https://github.com/IMMINJU/popdesk/tree/v0.4.0/docs); a page missing there did not exist yet.

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
- [Back up on a schedule](#back-up-on-a-schedule): a script for cron or a systemd timer that keeps a set number of dumps.

## Back up on a schedule

`deploy/backup.sh` does the three steps of [Back up](configuration.md#back-up) in one go, for cron or a systemd timer. It needs bash and Docker with Compose v2. Take it at the tag of the version you run, for example `https://raw.githubusercontent.com/IMMINJU/popdesk/v0.6.0/deploy/backup.sh`, put it next to your Compose file, and make it executable (`chmod +x backup.sh`). It writes the dump inside the app container with `popdesk backup`, copies it out with `docker compose cp`, deletes it in the container, and keeps it as `popdesk-YYYYMMDD-HHMMSS.dump` (UTC) in its folder. Then it deletes the oldest dumps so that a set number is left.

| Option | Environment variable | Default |
|---|---|---|
| `-f FILE` (more than once) | `POPDESK_BACKUP_COMPOSE_FILES` (separated by `:`) | `compose.yml` next to the script |
| `-p PROJECT` | `POPDESK_BACKUP_PROJECT` | what Compose picks (the folder name) |
| `-e ENV_FILE` | `POPDESK_BACKUP_ENV_FILE` | the `.env` next to the first Compose file |
| `-d DIR` | `POPDESK_BACKUP_DIR` | `./backups` |
| `-k KEEP` | `POPDESK_BACKUP_KEEP` | `24` |

When a step fails, the script ends with a non-zero exit code and leaves no dump under its final name: it copies to a hidden `.partial` file first and renames it only when the copy worked, and it deletes the file in the container either way. Dumps are written readable by their owner only. The script deletes only files named `popdesk-*.dump` in its folder, so give it a folder of its own.

Hourly, with cron, keeping a day of dumps:

```
0 * * * * cd /opt/popdesk/deploy && ./backup.sh -d /var/backups/popdesk -k 24 >> /var/log/popdesk-backup.log 2>&1
```

With a systemd timer, as `/etc/systemd/system/popdesk-backup.service` and `popdesk-backup.timer`:

```ini
[Unit]
Description=popdesk database backup

[Service]
Type=oneshot
WorkingDirectory=/opt/popdesk/deploy
ExecStart=/opt/popdesk/deploy/backup.sh -d /var/backups/popdesk -k 24
```

```ini
[Unit]
Description=popdesk database backup every hour

[Timer]
OnCalendar=hourly
Persistent=true

[Install]
WantedBy=timers.target
```

Then `systemctl enable --now popdesk-backup.timer`. A failed run shows in `systemctl status popdesk-backup.service` and in the journal.

- **Move the dumps off the host.** A dump on the Docker host is lost with the host. Copy the folder to other storage after each run, for example with `rsync` or your backup tool, and keep it as carefully as other personal data.
- **Back up the attachment files too.** With `POPDESK_STORAGE` set to `disk` or `s3` (now or ever before), the dump does not hold the files. Back up the folder or the bucket right after the dump, in the same job, so the two match ([What a backup holds](#what-a-backup-holds)).
- **Restore one now and then.** A backup is known to work only once it was restored. From time to time, restore a recent dump into a separate installation ([Restore](configuration.md#restore)), start the app on it, and run `popdesk attachments check`.

## How long a restore takes

Measured with 0.6.0 on a development machine (Docker Desktop, WSL 2), after a 24-hour run with hourly backups: 4,649 issues, 10,799 comments, 912 attachments in the database, a 105 MB database and a 96 MB dump. The [Restore](configuration.md#restore) steps took 34 seconds from stopping the app to a healthy app: 6 seconds for a new, empty database, 13 seconds for `popdesk restore`, and 15 seconds for the app to start. Afterwards the counts were the same, `popdesk attachments check` found nothing missing, and the audit log integrity check passed. Each hourly backup took 12 seconds while the app served requests.

Most of the restore time grows with the issues: the restore builds the search indexes again. The index for searches of one or two characters took 8 minutes 31 seconds for 100,000 issues of about 2,300 characters each ([upgrade.md](upgrade.md#upgrading-to-060)), so plan for at least that much for a database of that size, and time a restore of your own data.

## Before an upgrade

Back up the database, and then the attachment folder or bucket, before you install a new version. Migrations only move forward: to go back to an older version, you restore that backup. See [upgrade.md](upgrade.md#before-you-upgrade).
