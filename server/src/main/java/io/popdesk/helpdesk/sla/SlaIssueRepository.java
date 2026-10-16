package io.popdesk.helpdesk.sla;

import io.popdesk.helpdesk.Priority;
import io.popdesk.helpdesk.sla.SlaClocks.Clock;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The SLA clocks of issues as {@code sla-recalculate} reads and writes them (design.md 4절), whoever may see the
 * issues: the job works for popdesk itself and reads no customer data. The writes of a request go through
 * {@code IssueRepository}, in the UPDATE of their change.
 */
@Repository
class SlaIssueRepository {

    /** A live issue whose clocks run, as read for the job. */
    record Running(UUID id, Priority priority, IssueClocks clocks) {}

    /** An issue's clocks worked out again, and the version they were read at. */
    record Recalculated(UUID id, int readVersion, IssueClocks clocks) {}

    private final JdbcClient jdbc;

    SlaIssueRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Up to {@code limit} live issues with a running clock worked out with a version before {@code current}, in ID
     * order, locked {@code FOR NO KEY UPDATE}; a row a write holds is skipped, and a later run takes it (S8A-AC-21).
     * The index {@code issue_sla_running_idx} holds these.
     */
    List<Running> lockRunning(int current, int limit) {
        return jdbc.sql("select i.id, i.priority, " + IssueClocks.COLUMNS + " from issue i where i.sla_version < ? "
                        + "and (i.fr_since is not null or i.res_since is not null) and i.deleted_at is null "
                        + "order by i.id limit ? for no key update skip locked")
                .params(current, limit)
                .query((rs, n) -> new Running(rs.getObject("id", UUID.class), Priority.valueOf(rs.getString("priority")),
                        IssueClocks.read(rs)))
                .list();
    }

    /**
     * Writes the clocks of these issues in one statement, each only while its calculation version is still the one
     * read (S8A-AC-21), so a write that came in between is never undone. The marks and the first response's settling
     * time go with them, as every write of clocks writes them (slice 8b design.md 0절 1). The issue's {@code version}
     * and {@code updated_at} stay (S8A-AC-21, design.md 0절 9).
     *
     * @return the issues that changed (slice 8b design.md 0절 10)
     */
    List<UUID> updateClocks(List<Recalculated> issues) {
        if (issues.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("update issue i set sla_version = v.sla_version, fr_spent = v.fr_spent, fr_since = v.fr_since, "
                        + "fr_outcome = v.fr_outcome, fr_warn_at = v.fr_warn_at, fr_due_at = v.fr_due_at, "
                        + "res_spent = v.res_spent, res_since = v.res_since, res_outcome = v.res_outcome, "
                        + "res_warn_at = v.res_warn_at, res_due_at = v.res_due_at, sla_warn_at = v.sla_warn_at, "
                        + "sla_due_at = v.sla_due_at, fr_alerted = v.fr_alerted, res_alerted = v.res_alerted, "
                        + "fr_settled_at = v.fr_settled_at from unnest(cast(? as uuid[]), cast(? as integer[]), "
                        + "cast(? as integer[]), cast(? as bigint[]), cast(? as timestamptz[]), cast(? as text[]), "
                        + "cast(? as timestamptz[]), cast(? as timestamptz[]), cast(? as bigint[]), "
                        + "cast(? as timestamptz[]), cast(? as text[]), cast(? as timestamptz[]), "
                        + "cast(? as timestamptz[]), cast(? as timestamptz[]), cast(? as timestamptz[]), "
                        + "cast(? as text[]), cast(? as text[]), cast(? as timestamptz[])) "
                        + "as v(id, read_version, sla_version, fr_spent, fr_since, fr_outcome, fr_warn_at, fr_due_at, "
                        + "res_spent, res_since, res_outcome, res_warn_at, res_due_at, sla_warn_at, sla_due_at, "
                        + "fr_alerted, res_alerted, fr_settled_at) "
                        + "where i.id = v.id and i.sla_version = v.read_version returning i.id")
                .params(issues.stream().map(Recalculated::id).toArray(UUID[]::new),
                        texts(issues, r -> r.readVersion()),
                        texts(issues, r -> r.clocks().version()),
                        texts(issues, r -> r.clocks().firstResponse().spent()),
                        texts(issues, r -> r.clocks().firstResponse().since()),
                        texts(issues, r -> outcome(r.clocks().firstResponse())),
                        texts(issues, r -> r.clocks().firstResponse().warnAt()),
                        texts(issues, r -> r.clocks().firstResponse().dueAt()),
                        texts(issues, r -> r.clocks().resolution().spent()),
                        texts(issues, r -> r.clocks().resolution().since()),
                        texts(issues, r -> outcome(r.clocks().resolution())),
                        texts(issues, r -> r.clocks().resolution().warnAt()),
                        texts(issues, r -> r.clocks().resolution().dueAt()),
                        texts(issues, r -> r.clocks().warnAt()),
                        texts(issues, r -> r.clocks().dueAt()),
                        texts(issues, r -> IssueClocks.name(r.clocks().firstResponseAlerted())),
                        texts(issues, r -> IssueClocks.name(r.clocks().resolutionAlerted())),
                        texts(issues, r -> r.clocks().firstResponseSettledAt()))
                .query(UUID.class).list();
    }

    /**
     * How many live issues with a running clock are still worked out with a version before {@code current}, locked
     * rows included: the recalculation has ended when there are none (slice 8b design.md 0절 9).
     */
    long countRunningBefore(int current) {
        return jdbc.sql("select count(*) from issue i where i.sla_version < ? "
                        + "and (i.fr_since is not null or i.res_since is not null) and i.deleted_at is null")
                .param(current).query(Long.class).single();
    }

    /** A live issue of the current version with something to tell, as the alert step reads it. */
    record Due(UUID id, long companyId, Long assigneeId, IssueClocks clocks) {}

    /**
     * The next time the alert step has something to tell about an issue, as SQL on the issue alias {@code i}: the
     * expression of the index {@code issue_sla_alert_idx} (V20), so that the condition and the order use it.
     */
    private static final String NEXT_ALERT = "least("
            + "case when i.fr_since is null then null when i.fr_alerted is null then i.fr_warn_at "
            + "when i.fr_alerted = 'WARNING' then i.fr_due_at end, "
            + "case when i.res_since is null then null when i.res_alerted is null then i.res_warn_at "
            + "when i.res_alerted = 'WARNING' then i.res_due_at end)";

    /**
     * Up to {@code limit} live issues worked out with the version {@code current} whose next time to tell is at or
     * before {@code now}, the earliest first, locked {@code FOR NO KEY UPDATE}; a row a write holds is skipped, and a
     * later run takes it (slice 8b design.md 0절 2, 0절 4). An issue of an older version is left to the
     * recalculation (slice 8b acceptance.md 5절 1). The policy is not locked (0절 6).
     */
    List<Due> lockDue(int current, Instant now, int limit) {
        return jdbc.sql("select i.id, i.company_id, i.assignee_id, " + IssueClocks.COLUMNS + " from issue i "
                        + "where i.deleted_at is null and i.sla_version = ? and " + NEXT_ALERT + " <= ? "
                        + "order by " + NEXT_ALERT + ", i.id limit ? for no key update of i skip locked")
                .params(current, Timestamp.from(now), limit)
                .query((rs, n) -> new Due(rs.getObject("id", UUID.class), rs.getLong("company_id"),
                        rs.getObject("assignee_id", Long.class), IssueClocks.read(rs)))
                .list();
    }

    /**
     * Writes the marks of an issue the alert step locked, only while it is still of the version read. The issue's
     * {@code version} and {@code updated_at} stay and its history gets nothing (S8B-AC-07).
     *
     * @return whether the issue changed
     */
    boolean updateMarks(UUID id, int version, IssueClocks clocks) {
        return jdbc.sql("update issue set fr_alerted = ?, res_alerted = ? where id = ? and sla_version = ?")
                .params(IssueClocks.name(clocks.firstResponseAlerted()), IssueClocks.name(clocks.resolutionAlerted()),
                        id, version)
                .update() == 1;
    }

    private static String outcome(Clock clock) {
        return clock.outcome() == null ? null : clock.outcome().name();
    }

    /** One column of the rows as text the database casts back: numbers, ISO instants, names; null stays null. */
    private static String[] texts(List<Recalculated> issues, Function<Recalculated, Object> column) {
        return issues.stream().map(column).map(v -> v == null ? null
                : v instanceof Instant at ? at.toString() : v.toString()).toArray(String[]::new);
    }
}
