-- The trash (TRASH-1 to TRASH-4) and the automatic closing (STATUS-5). Slice 7a design section 1.

-- An issue in the trash has deleted_at and no purged_at; a purged issue has both, and keeps its row with
-- an empty title and content (TRASH-3); a live issue has neither. deleted_by is the admin who deleted it.
-- auto_close_from is when a RESOLVED issue became RESOLVED, or was restored from the trash later; popdesk
-- closes it 7 days after (STATUS-5). Null in every other status.
ALTER TABLE issue ADD COLUMN deleted_at timestamptz;
ALTER TABLE issue ADD COLUMN deleted_by bigint REFERENCES account (id);
ALTER TABLE issue ADD COLUMN purged_at timestamptz;
ALTER TABLE issue ADD COLUMN auto_close_from timestamptz;

-- The RESOLVED issues of an earlier version close 7 days after they were resolved: the first run of the
-- job after the upgrade closes those resolved longer ago (deploy/upgrade/README.md).
UPDATE issue SET auto_close_from = resolved_at WHERE status = 'RESOLVED';

-- The trash list and the hourly purge.
CREATE INDEX issue_trash_idx ON issue (deleted_at) WHERE deleted_at IS NOT NULL AND purged_at IS NULL;

-- The automatic closing, every 10 minutes.
CREATE INDEX issue_auto_close_idx ON issue (auto_close_from) WHERE status = 'RESOLVED' AND deleted_at IS NULL;
