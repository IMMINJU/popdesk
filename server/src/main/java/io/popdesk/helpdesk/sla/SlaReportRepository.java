package io.popdesk.helpdesk.sla;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The counts of a recalculation (table {@code sla_recalc_report}, slice 8b design.md 1절): one open row collects
 * what every batch of the job and every write that caught an issue up changed, until the recalculation ends.
 */
@Repository
class SlaReportRepository {

    /** What a recalculation changed so far, counted per issue (S8B-AC-12). */
    record Counts(int becameBreached, int becameWarning, int released) {

        static final Counts NONE = new Counts(0, 0, 0);

        Counts plus(SlaAlerts.Change change) {
            if (change == null) {
                return this;
            }
            return switch (change) {
                case BECAME_BREACHED -> new Counts(becameBreached + 1, becameWarning, released);
                case BECAME_WARNING -> new Counts(becameBreached, becameWarning + 1, released);
                case RELEASED -> new Counts(becameBreached, becameWarning, released + 1);
            };
        }

        boolean none() {
            return becameBreached == 0 && becameWarning == 0 && released == 0;
        }
    }

    /** The open row, locked. */
    record Open(long id, Counts counts) {}

    private final JdbcClient jdbc;

    SlaReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Adds the counts to the open row, which is made when there is none (slice 8b design.md 0절 8). One statement,
     * which locks the row until the end of the transaction; the caller holds the issue rows it counted already.
     * Nothing is sent for counts of 0.
     */
    void add(Counts counts, Instant now) {
        if (counts.none()) {
            return;
        }
        jdbc.sql("insert into sla_recalc_report (tenant_id, opened_at, became_breached, became_warning, released) "
                        + "values (1, ?, ?, ?, ?) on conflict (tenant_id) where closed_at is null do update set "
                        + "became_breached = sla_recalc_report.became_breached + excluded.became_breached, "
                        + "became_warning = sla_recalc_report.became_warning + excluded.became_warning, "
                        + "released = sla_recalc_report.released + excluded.released")
                .params(Timestamp.from(now), counts.becameBreached(), counts.becameWarning(), counts.released())
                .update();
    }

    /** The open row locked {@code FOR NO KEY UPDATE}, if there is one (slice 8b design.md 0절 9). */
    Optional<Open> lockOpen() {
        return jdbc.sql("select id, became_breached, became_warning, released from sla_recalc_report "
                        + "where closed_at is null for no key update")
                .query((rs, n) -> new Open(rs.getLong("id"), new Counts(rs.getInt("became_breached"),
                        rs.getInt("became_warning"), rs.getInt("released"))))
                .optional();
    }

    /** Closes the open row the caller locked; it is kept. */
    void close(long id, Instant now) {
        jdbc.sql("update sla_recalc_report set closed_at = ? where id = ?").params(Timestamp.from(now), id).update();
    }
}
