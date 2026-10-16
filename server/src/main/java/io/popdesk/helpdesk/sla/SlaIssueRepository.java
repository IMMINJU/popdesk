package io.popdesk.helpdesk.sla;

import io.popdesk.helpdesk.Priority;
import io.popdesk.helpdesk.sla.SlaClocks.Clock;
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
     * read (S8A-AC-21), so a write that came in between is never undone. The issue's {@code version} and
     * {@code updated_at} stay (S8A-AC-21, design.md 0절 9).
     *
     * @return how many issues changed
     */
    int updateClocks(List<Recalculated> issues) {
        if (issues.isEmpty()) {
            return 0;
        }
        return jdbc.sql("update issue i set sla_version = v.sla_version, fr_spent = v.fr_spent, fr_since = v.fr_since, "
                        + "fr_outcome = v.fr_outcome, fr_warn_at = v.fr_warn_at, fr_due_at = v.fr_due_at, "
                        + "res_spent = v.res_spent, res_since = v.res_since, res_outcome = v.res_outcome, "
                        + "res_warn_at = v.res_warn_at, res_due_at = v.res_due_at, sla_warn_at = v.sla_warn_at, "
                        + "sla_due_at = v.sla_due_at from unnest(cast(? as uuid[]), cast(? as integer[]), "
                        + "cast(? as integer[]), cast(? as bigint[]), cast(? as timestamptz[]), cast(? as text[]), "
                        + "cast(? as timestamptz[]), cast(? as timestamptz[]), cast(? as bigint[]), "
                        + "cast(? as timestamptz[]), cast(? as text[]), cast(? as timestamptz[]), "
                        + "cast(? as timestamptz[]), cast(? as timestamptz[]), cast(? as timestamptz[])) "
                        + "as v(id, read_version, sla_version, fr_spent, fr_since, fr_outcome, fr_warn_at, fr_due_at, "
                        + "res_spent, res_since, res_outcome, res_warn_at, res_due_at, sla_warn_at, sla_due_at) "
                        + "where i.id = v.id and i.sla_version = v.read_version")
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
                        texts(issues, r -> r.clocks().dueAt()))
                .update();
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
