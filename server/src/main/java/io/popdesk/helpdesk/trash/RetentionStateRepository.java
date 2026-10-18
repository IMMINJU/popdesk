package io.popdesk.helpdesk.trash;

import io.popdesk.helpdesk.trash.RetentionSchedule.State;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The one row of {@code issue_retention}: what automatic issue cleanup saw at its last run (V23, PII-6). */
@Repository
class RetentionStateRepository {

    private final JdbcClient jdbc;

    RetentionStateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The row, locked {@code FOR NO KEY UPDATE} until the end of the caller's transaction. */
    State lock() {
        return jdbc.sql("select period_seconds, moves_from, last_moved_at from issue_retention where id = 1 "
                        + "for no key update")
                .query((rs, n) -> new State(rs.getObject("period_seconds", Long.class),
                        instant(rs.getTimestamp("moves_from")), instant(rs.getTimestamp("last_moved_at"))))
                .single();
    }

    void update(State state) {
        jdbc.sql("update issue_retention set period_seconds = ?, moves_from = ?, last_moved_at = ? where id = 1")
                .params(state.periodSeconds(), timestamp(state.movesFrom()), timestamp(state.lastMovedAt()))
                .update();
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static Timestamp timestamp(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
