-- SLA alerts and statistics (SLA-5, SLA-7, STATS-4). Slice 8b design section 1.

-- What the alert step of sla-recalculate last told about each goal of an issue: null, WARNING, or BREACHED. A mark
-- stays while the clock is stopped (settled, or paused in the trash), and a write that takes the goal back to an
-- earlier step lowers it. fr_settled_at is when the first response goal was settled as MET or MISSED; it is the
-- first response time when a response settled it, and it never changes once set.
ALTER TABLE issue
    ADD COLUMN fr_alerted text CHECK (fr_alerted IN ('WARNING', 'BREACHED')),
    ADD COLUMN res_alerted text CHECK (res_alerted IN ('WARNING', 'BREACHED')),
    ADD COLUMN fr_settled_at timestamptz;

-- The next time the alert step has something to tell about an issue: for each goal, nothing while its clock is
-- stopped or it was told it is overdue, else its warning time until it was told of that, then its due time; the
-- earlier of the two goals (least skips nulls). The alert step asks for this same expression.
CREATE INDEX issue_sla_alert_idx ON issue ((least(
        CASE WHEN fr_since IS NULL THEN NULL
             WHEN fr_alerted IS NULL THEN fr_warn_at
             WHEN fr_alerted = 'WARNING' THEN fr_due_at END,
        CASE WHEN res_since IS NULL THEN NULL
             WHEN res_alerted IS NULL THEN res_warn_at
             WHEN res_alerted = 'WARNING' THEN res_due_at END)))
    WHERE deleted_at IS NULL AND sla_version IS NOT NULL;

-- Goals settled before this version: a first response settles at the response, or else when the issue was
-- resolved or closed. Only development installations have such issues (0.8.0 ships slices 8a and 8b together).
UPDATE issue SET fr_settled_at = coalesce(first_response_at, resolved_at, closed_at)
WHERE fr_outcome IN ('MET', 'MISSED');

-- The counts of one recalculation, from the first change it made until no running issue of an older policy
-- version is left (closed_at). One open row per tenant; closed rows are kept.
CREATE TABLE sla_recalc_report (
    id bigserial PRIMARY KEY,
    tenant_id bigint NOT NULL,
    opened_at timestamptz NOT NULL,
    closed_at timestamptz,
    became_breached integer NOT NULL DEFAULT 0 CHECK (became_breached >= 0),
    became_warning integer NOT NULL DEFAULT 0 CHECK (became_warning >= 0),
    released integer NOT NULL DEFAULT 0 CHECK (released >= 0)
);
CREATE UNIQUE INDEX sla_recalc_report_open_idx ON sla_recalc_report (tenant_id) WHERE closed_at IS NULL;

-- The counts of a SLA_RECALCULATED item, copied from its report: notification refers to no helpdesk table (V3).
ALTER TABLE notification
    ADD COLUMN sla_became_breached integer,
    ADD COLUMN sla_became_warning integer,
    ADD COLUMN sla_released integer;
