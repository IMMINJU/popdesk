-- The audit log's hash chain, its filters, and the outside copy's place in the send queue (slice 1b).
-- V7 (Java) computes seq, prev_hash and hash for the rows that are there and then sets NOT NULL and
-- the unique constraints.

-- seq is the row's place in the chain, from 1. hash is SHA-256 of prev_hash (64 hex characters) and
-- the row's canonical JSON; prev_hash is the hash of the row before, 64 zeros for the first
-- (docs/integrate/conventions.md "Audit log hash chain"). No trigger on this table: the app and the
-- commands in the container append through one class.
ALTER TABLE audit_log
    ADD COLUMN seq bigint,
    ADD COLUMN prev_hash text,
    ADD COLUMN hash text;

-- Owned by helpdesk (audit log). One row: the end of the chain (head) and where it starts (anchor).
-- Appending locks this row, so the chain stays one line. The anchor is the seq and hash of the last
-- entry the retention period removed; 0 and 64 zeros while nothing was removed.
CREATE TABLE audit_chain (
    tenant_id bigint NOT NULL PRIMARY KEY,
    head_seq bigint NOT NULL,
    head_hash text NOT NULL,
    anchor_seq bigint NOT NULL,
    anchor_hash text NOT NULL
);

INSERT INTO audit_chain (tenant_id, head_seq, head_hash, anchor_seq, anchor_hash)
VALUES (1, 0, repeat('0', 64), 0, repeat('0', 64));

-- The filters of the audit log (S1B-AC-03 to S1B-AC-05): kind, who did it, what it was done to.
CREATE INDEX audit_log_action_idx ON audit_log (action);
CREATE INDEX audit_log_actor_idx ON audit_log ((actor ->> 'accountId'));
CREATE INDEX audit_log_target_account_idx ON audit_log ((target ->> 'accountId'))
    WHERE (target ->> 'accountId') IS NOT NULL;
CREATE INDEX audit_log_target_code_idx ON audit_log ((target ->> 'code'))
    WHERE (target ->> 'code') IS NOT NULL;

-- The outside copy of the audit log is a channel of the send queue. Its row holds the audit entry's
-- ID only (no foreign key, as for the other IDs here); the body is read when it is sent.
ALTER TABLE outbox
    DROP CONSTRAINT outbox_channel_check,
    ADD CONSTRAINT outbox_channel_check CHECK (channel IN ('MAIL', 'WEBHOOK', 'AUDIT_FORWARD')),
    ADD COLUMN audit_id bigint;

ALTER TABLE delivery_status
    DROP CONSTRAINT delivery_status_channel_check,
    ADD CONSTRAINT delivery_status_channel_check CHECK (channel IN ('MAIL', 'WEBHOOK', 'AUDIT_FORWARD'));
