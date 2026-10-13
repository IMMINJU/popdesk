-- Images in the content of issues and comments (ATT-6, ATT-7). A temporary image is uploaded before
-- the issue or comment that shows it is saved, so its file is stored while it has no issue yet. The
-- save attaches it: it gets its issue_id, and its comment_id when it belongs to a comment.
--
-- inline marks the rows that were uploaded as images for a content. Whether one is attached is told
-- by issue_id and how old it is by created_at, so there is no state and no table for "temporary".
-- Every attachment that is there was uploaded as a file: false.
ALTER TABLE attachment ADD COLUMN inline boolean NOT NULL DEFAULT false;

-- A stored attachment needs its issue (V4), but for a temporary image.
ALTER TABLE attachment DROP CONSTRAINT attachment_stored_has_issue_check;
ALTER TABLE attachment ADD CONSTRAINT attachment_stored_has_issue_check
    CHECK (state <> 'stored' OR issue_id IS NOT NULL OR inline);

-- The temporary images of one account: counted at every upload (20 images, 50 MB), and what the
-- cleanup of expired ones reads.
CREATE INDEX attachment_temporary_idx ON attachment (uploaded_by) WHERE inline AND issue_id IS NULL;

-- A new comment's images are attached before the comment row is inserted, because attachment rows
-- are changed before any INSERT (architecture.md "Concurrency and lock order", steps 2 and 3). The
-- comment's ID is taken first and the key is checked at commit, like the key to the issue (V2).
ALTER TABLE attachment ALTER CONSTRAINT attachment_comment_id_fkey DEFERRABLE INITIALLY DEFERRED;
