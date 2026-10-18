-- Company export and automatic issue cleanup (PII-8, PII-6). Slice 10b design section 1.

-- Company exports (PII-8): one ZIP of one company's data, written in the background. The file is in
-- POPDESK_EXPORT_DIR, named after the ID; the row stays after the file is deleted (EXPIRED).
CREATE TABLE company_export (
    id uuid PRIMARY KEY,
    tenant_id bigint NOT NULL,
    company_id bigint NOT NULL REFERENCES company (id),
    requested_by bigint NOT NULL REFERENCES account (id),
    include_internal boolean NOT NULL,
    include_private boolean NOT NULL,
    state text NOT NULL CHECK (state IN ('RUNNING', 'DONE', 'FAILED', 'EXPIRED')),
    -- Why a FAILED export stopped: INTERRUPTED, STORAGE, DISK, or UNEXPECTED; null otherwise.
    failure text,
    -- Bytes of the ZIP, set when it became DONE.
    size_bytes bigint,
    requested_at timestamptz NOT NULL,
    -- When it became DONE or FAILED.
    finished_at timestamptz
);
-- One export runs at a time in the whole installation (acceptance.md 5절 3); the app checks it
-- first, and this keeps it in the database too.
CREATE UNIQUE INDEX company_export_running_key ON company_export (tenant_id) WHERE state = 'RUNNING';
CREATE INDEX company_export_company ON company_export (company_id, requested_at DESC);

-- What automatic issue cleanup saw at its last run (PII-6). One row. The setting is an environment
-- variable, so its value of yesterday is kept here to tell a first day.
CREATE TABLE issue_retention (
    id smallint PRIMARY KEY CHECK (id = 1),
    tenant_id bigint NOT NULL,
    -- The period seen at the last run, in seconds; null while the cleanup is off.
    period_seconds bigint,
    -- No issue is moved before this time: the wait after the cleanup was turned on or shortened.
    moves_from timestamptz,
    -- The last run that moved issues: the next one comes 24 hours later.
    last_moved_at timestamptz
);
INSERT INTO issue_retention (id, tenant_id) VALUES (1, 1);

CREATE INDEX issue_closed_for_retention ON issue (closed_at) WHERE status = 'CLOSED' AND deleted_at IS NULL;

-- When the issue was last restored from the trash (acceptance.md 5절 12). Automatic cleanup counts
-- the period from the later of closed_at and restored_at.
ALTER TABLE issue ADD COLUMN restored_at timestamptz;

-- The numbers of an ISSUE_RETENTION_ARMED item (acceptance.md 5절 14); null for the other kinds.
ALTER TABLE notification ADD COLUMN retention_count integer, ADD COLUMN retention_days integer;
