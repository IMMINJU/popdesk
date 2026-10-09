package io.popdesk.notification.outbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Rows of the outbox (table {@code outbox}). */
@Repository
class OutboxRepository {

    static final String MAIL = "MAIL";
    static final String QUEUED = "queued";

    private final JdbcClient jdbc;

    OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A new queued row, due now: the first attempt is at the time it was queued. */
    void insert(String channel, String kind, Long recipientAccountId, UUID issueId, Long commentId, Instant now) {
        jdbc.sql("insert into outbox (tenant_id, channel, kind, recipient_account_id, issue_id, comment_id, state, "
                        + "attempts, next_attempt_at, created_at) values (1, ?, ?, ?, ?, ?, ?, 0, ?, ?)")
                .params(channel, kind, recipientAccountId, issueId, commentId, QUEUED, Timestamp.from(now),
                        Timestamp.from(now))
                .update();
    }
}
