-- Talk (slice 4): edited and deleted comments, mentions, inbox items about issues, and the company of
-- a membership request email. Columns, one table, and indexes only: rows that exist are not changed,
-- so every comment there is has never been edited, is not deleted, and mentions nobody, and every
-- inbox item there is has no issue.

-- When the author last changed the content; null when it never changed (S4-AC-07). A deleted comment
-- keeps its row as a trace: content becomes the empty text and deleted_at and deleted_by are set
-- (COM-7, S4-AC-10). The content it had is kept nowhere.
ALTER TABLE comment
    ADD COLUMN edited_at timestamptz,
    ADD COLUMN deleted_at timestamptz,
    ADD COLUMN deleted_by bigint REFERENCES account (id);

-- Owned by helpdesk (comments). The accounts a comment mentions, as its author chose them (MNT-1).
-- The rows stay when the comment is deleted and when the account can no longer see the issue
-- (S4-AC-26).
CREATE TABLE comment_mention (
    comment_id bigint NOT NULL REFERENCES comment (id),
    account_id bigint NOT NULL REFERENCES account (id),
    tenant_id bigint NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (comment_id, account_id)
);

-- The comments that mention an account.
CREATE INDEX comment_mention_account_idx ON comment_mention (account_id);

-- An inbox item about an issue: the issue, the comment it is about if any, and who caused it. All
-- null for the items of admins that are about no issue. IDs without foreign keys, like the other IDs
-- notification keeps; the title and the key are read from the issue when the inbox is read (INBOX-1).
ALTER TABLE notification
    ADD COLUMN issue_id uuid,
    ADD COLUMN comment_id bigint,
    ADD COLUMN actor_id bigint;

-- The unread count of an account, asked every 60 seconds (S4-AC-43).
CREATE INDEX notification_unread_idx ON notification (account_id) WHERE read_at IS NULL;

-- The items of a comment, removed when the comment is deleted (S4-AC-16).
CREATE INDEX notification_comment_idx ON notification (comment_id) WHERE comment_id IS NOT NULL;

-- The items of an issue, for deleting an issue for good (slice 7).
CREATE INDEX notification_issue_idx ON notification (issue_id) WHERE issue_id IS NOT NULL;

-- The company a MEMBERSHIP_REQUESTED email is about (S4-AC-51); null for every other row. The code as
-- text, like notification.company_code: the request may be closed by the time the email is sent.
ALTER TABLE outbox ADD COLUMN company_code text;
