-- SLA policy and due times (SLA-1 to SLA-6, SLA-8). Slice 8a design section 1.

-- Each version of the policy is a set of rows that never change: its goals, business hours, and holidays,
-- and the time zone they are read in. starts_at is when popdesk began to count with it. changed_by is the
-- admin who made it, null for popdesk itself (version 1, and a version made because POPDESK_TIMEZONE
-- changed). time_zone is null for version 1 only, until the first run of sla-recalculate fills it in.
CREATE TABLE sla_policy (
    version integer PRIMARY KEY,
    tenant_id bigint NOT NULL,
    time_zone text,
    starts_at timestamptz NOT NULL,
    changed_by bigint REFERENCES account (id),
    changed_at timestamptz NOT NULL
);

-- The goals of each priority in business minutes; null is no goal.
CREATE TABLE sla_target (
    version integer NOT NULL REFERENCES sla_policy (version),
    tenant_id bigint NOT NULL,
    priority text NOT NULL CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    first_response_minutes integer CHECK (first_response_minutes BETWEEN 1 AND 525600),
    resolution_minutes integer CHECK (resolution_minutes BETWEEN 1 AND 525600),
    PRIMARY KEY (version, priority)
);

-- One span per ISO weekday (1 is Monday); a weekday without business hours has no row, and a version without
-- any row counts 24 hours every day. closes may be 24:00:00, the end of the day.
CREATE TABLE sla_hours (
    version integer NOT NULL REFERENCES sla_policy (version),
    tenant_id bigint NOT NULL,
    weekday smallint NOT NULL CHECK (weekday BETWEEN 1 AND 7),
    opens time NOT NULL,
    closes time NOT NULL CHECK (closes > opens),
    PRIMARY KEY (version, weekday)
);

CREATE TABLE sla_holiday (
    version integer NOT NULL REFERENCES sla_policy (version),
    tenant_id bigint NOT NULL,
    day date NOT NULL,
    PRIMARY KEY (version, day)
);

-- An installation starts with SLA off: version 1 without goals.
INSERT INTO sla_policy (version, tenant_id, time_zone, starts_at, changed_by, changed_at)
VALUES (1, 1, NULL, now(), NULL, now());

-- The two clocks of an issue under SLA (design section 0, 1). sla_version is the policy version its due times
-- were worked out with; null for an issue that is not under SLA, which every issue of an earlier version is
-- (SLA-8). For each goal: the business seconds counted before (spent), the start of the stretch that runs now
-- (since, null while stopped), and how it was settled (outcome: null while it runs or is paused in the trash;
-- NONE when it stopped without a goal). The warning and due times are those of the running stretch, or of the
-- moment the clock stopped. sla_warn_at and sla_due_at are the earliest of the running goals, for the list.
ALTER TABLE issue ADD COLUMN sla_version integer;
ALTER TABLE issue ADD COLUMN fr_spent bigint;
ALTER TABLE issue ADD COLUMN fr_since timestamptz;
ALTER TABLE issue ADD COLUMN fr_warn_at timestamptz;
ALTER TABLE issue ADD COLUMN fr_due_at timestamptz;
ALTER TABLE issue ADD COLUMN fr_outcome text CHECK (fr_outcome IN ('MET', 'MISSED', 'NONE'));
ALTER TABLE issue ADD COLUMN res_spent bigint;
ALTER TABLE issue ADD COLUMN res_since timestamptz;
ALTER TABLE issue ADD COLUMN res_warn_at timestamptz;
ALTER TABLE issue ADD COLUMN res_due_at timestamptz;
ALTER TABLE issue ADD COLUMN res_outcome text CHECK (res_outcome IN ('MET', 'MISSED', 'NONE'));
ALTER TABLE issue ADD COLUMN sla_warn_at timestamptz;
ALTER TABLE issue ADD COLUMN sla_due_at timestamptz;

-- The list filters sla=breached and sla=warning, and sort=slaDue.
CREATE INDEX issue_sla_due_idx ON issue (sla_due_at) WHERE deleted_at IS NULL AND sla_due_at IS NOT NULL;
CREATE INDEX issue_sla_warn_idx ON issue (sla_warn_at) WHERE deleted_at IS NULL AND sla_warn_at IS NOT NULL;
-- sla-recalculate: the issues whose clocks run, by their calculation version.
CREATE INDEX issue_sla_running_idx ON issue (sla_version)
    WHERE deleted_at IS NULL AND (fr_since IS NOT NULL OR res_since IS NOT NULL);
