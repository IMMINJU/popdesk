package io.popdesk.helpdesk.sla;

import io.popdesk.helpdesk.sla.SlaClocks.Clock;
import io.popdesk.helpdesk.sla.SlaClocks.Outcome;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The two SLA clocks of an issue under SLA and the policy version they were worked out with (design.md 1절), as the
 * columns of table {@code issue} keep them. An issue that is not under SLA has none: its {@code sla_version} is null.
 */
public record IssueClocks(int version, Clock firstResponse, Clock resolution) {

    /** The columns, on the issue alias {@code i}, that {@link #read} reads. */
    public static final String COLUMNS = "i.sla_version, i.fr_spent, i.fr_since, i.fr_outcome, i.fr_warn_at, "
            + "i.fr_due_at, i.res_spent, i.res_since, i.res_outcome, i.res_warn_at, i.res_due_at";

    /** What an UPDATE of the issue sets to write the clocks, with the parameters of {@link #params}. */
    public static final String SET = "sla_version = ?, fr_spent = ?, fr_since = ?, fr_outcome = ?, fr_warn_at = ?, "
            + "fr_due_at = ?, res_spent = ?, res_since = ?, res_outcome = ?, res_warn_at = ?, res_due_at = ?, "
            + "sla_warn_at = ?, sla_due_at = ?";

    /** The columns an INSERT of a new issue gives, in the order of {@link #params}. */
    public static final String INSERT = "sla_version, fr_spent, fr_since, fr_outcome, fr_warn_at, fr_due_at, "
            + "res_spent, res_since, res_outcome, res_warn_at, res_due_at, sla_warn_at, sla_due_at";

    /** The clocks of the row, or null when the issue is not under SLA. */
    public static IssueClocks read(ResultSet rs) throws SQLException {
        int version = rs.getInt("sla_version");
        if (rs.wasNull()) {
            return null;
        }
        return new IssueClocks(version, clock(rs, "fr_"), clock(rs, "res_"));
    }

    private static Clock clock(ResultSet rs, String prefix) throws SQLException {
        String outcome = rs.getString(prefix + "outcome");
        return new Clock(rs.getLong(prefix + "spent"), instant(rs, prefix + "since"),
                outcome == null ? null : Outcome.valueOf(outcome), instant(rs, prefix + "warn_at"),
                instant(rs, prefix + "due_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /** The parameters of {@link #SET}, in order. */
    public Object[] params() {
        List<Object> out = new ArrayList<>();
        out.add(version);
        for (Clock clock : List.of(firstResponse, resolution)) {
            out.add(clock.spent());
            out.add(timestamp(clock.since()));
            out.add(clock.outcome() == null ? null : clock.outcome().name());
            out.add(timestamp(clock.warnAt()));
            out.add(timestamp(clock.dueAt()));
        }
        out.add(timestamp(warnAt()));
        out.add(timestamp(dueAt()));
        return out.toArray();
    }

    /** The earliest warning time of the running goals, or null when none runs with a goal (design.md 0절 3). */
    public Instant warnAt() {
        return running().map(Clock::warnAt).filter(t -> t != null).min(Instant::compareTo).orElse(null);
    }

    /** The earliest due time of the running goals, or null when none runs with a goal (design.md 0절 3). */
    public Instant dueAt() {
        return running().map(Clock::dueAt).filter(t -> t != null).min(Instant::compareTo).orElse(null);
    }

    private Stream<Clock> running() {
        return Stream.of(firstResponse, resolution).filter(Clock::running);
    }

    private static Timestamp timestamp(Instant at) {
        return at == null ? null : Timestamp.from(at);
    }
}
