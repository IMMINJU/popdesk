-- A file sent with a new issue is pending without an issue (V2). When that filing fails, the
-- attachment cleanup ends the row as purging and then purged (S0-AC-62, S0-AC-63), still without an
-- issue. Only a stored attachment needs its issue.
ALTER TABLE attachment DROP CONSTRAINT attachment_stored_has_issue_check;
ALTER TABLE attachment ADD CONSTRAINT attachment_stored_has_issue_check CHECK (state <> 'stored' OR issue_id IS NOT NULL);
