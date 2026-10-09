package io.popdesk.notification.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Rows of the outbox (table {@code outbox}) and the last success per channel (table {@code delivery_status}). */
@Repository
public class OutboxRepository {

    public static final String MAIL = "MAIL";
    public static final String WEBHOOK = "WEBHOOK";
    static final String QUEUED = "queued";
    static final String FAILED = "failed";

    /** A row as the send loop reads it. */
    record OutboxRow(long id, String channel, String kind, Long recipientAccountId, UUID issueId, Long commentId,
            int attempts, Instant firstFailedAt, Instant createdAt) {}

    /** A {@code failed} row for the system status (S0-AC-81). */
    public record FailedRow(long id, String channel, Instant createdAt, String lastErrorKind, Long recipientAccountId) {}

    /** Rows of one channel and state. */
    public record Count(String channel, String state, long rows) {}

    private static final String COLUMNS = "id, channel, kind, recipient_account_id, issue_id, comment_id, attempts, "
            + "first_failed_at, created_at";

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

    /**
     * The oldest queued row of the channel that is due, locked; rows another transaction has locked are
     * skipped (S0-AC-67).
     */
    Optional<OutboxRow> lockNextDue(String channel, Instant now) {
        return jdbc.sql("select " + COLUMNS + " from outbox where channel = ? and state = ? and next_attempt_at <= ? "
                        + "order by id limit 1 for update skip locked")
                .params(channel, QUEUED, Timestamp.from(now))
                .query(OutboxRepository::row).optional();
    }

    void delete(long id) {
        jdbc.sql("delete from outbox where id = ?").param(id).update();
    }

    /** A failed attempt that will be tried again at {@code nextAttemptAt}. */
    void updateRetry(long id, int attempts, Instant firstFailedAt, Instant nextAttemptAt, String errorKind) {
        jdbc.sql("update outbox set attempts = ?, first_failed_at = ?, next_attempt_at = ?, last_error_kind = ? "
                        + "where id = ?")
                .params(attempts, Timestamp.from(firstFailedAt), Timestamp.from(nextAttemptAt), errorKind, id)
                .update();
    }

    /** A failed attempt after which the row is not tried again (S0-AC-68). */
    void updateFailed(long id, int attempts, Instant firstFailedAt, Instant failedAt, String errorKind) {
        jdbc.sql("update outbox set state = ?, attempts = ?, first_failed_at = ?, failed_at = ?, last_error_kind = ? "
                        + "where id = ?")
                .params(FAILED, attempts, Timestamp.from(firstFailedAt), Timestamp.from(failedAt), errorKind, id)
                .update();
    }

    /** Deletes the {@code failed} rows that failed at or before {@code cutoff}; returns how many. */
    int deleteFailedBefore(Instant cutoff) {
        return jdbc.sql("delete from outbox where state = ? and failed_at <= ?")
                .params(FAILED, Timestamp.from(cutoff)).update();
    }

    /** Rows per channel and state; a pair with no rows is missing. */
    public List<Count> counts() {
        return jdbc.sql("select channel, state, count(*) as n from outbox group by channel, state")
                .query((rs, n) -> new Count(rs.getString("channel"), rs.getString("state"), rs.getLong("n"))).list();
    }

    public boolean anyFailed() {
        return jdbc.sql("select exists (select 1 from outbox where state = ?)").param(FAILED)
                .query(Boolean.class).single();
    }

    /** {@code failed} rows, the latest failure first. */
    public List<FailedRow> listFailed(long offset, int limit) {
        return jdbc.sql("select id, channel, created_at, last_error_kind, recipient_account_id from outbox "
                        + "where state = ? order by failed_at desc, id desc offset ? limit ?")
                .params(FAILED, offset, limit)
                .query((rs, n) -> new FailedRow(rs.getLong("id"), rs.getString("channel"),
                        instant(rs.getTimestamp("created_at")), rs.getString("last_error_kind"),
                        rs.getObject("recipient_account_id", Long.class)))
                .list();
    }

    public long countFailed() {
        return jdbc.sql("select count(*) from outbox where state = ?").param(FAILED).query(Long.class).single();
    }

    // Last success per channel

    /** Makes the channel's status row, once, so later sends only update it. */
    void insertDeliveryStatus(String channel) {
        jdbc.sql("insert into delivery_status (channel, tenant_id, last_success_at) values (?, 1, null) "
                        + "on conflict (channel) do nothing")
                .param(channel).update();
    }

    void updateLastSuccess(String channel, Instant at) {
        jdbc.sql("insert into delivery_status (channel, tenant_id, last_success_at) values (?, 1, ?) "
                        + "on conflict (channel) do update set last_success_at = excluded.last_success_at")
                .params(channel, Timestamp.from(at)).update();
    }

    public Optional<Instant> lastSuccess(String channel) {
        return jdbc.sql("select last_success_at from delivery_status where channel = ?").param(channel)
                .query((rs, n) -> Optional.ofNullable(instant(rs.getTimestamp("last_success_at"))))
                .optional().flatMap(at -> at);
    }

    private static OutboxRow row(ResultSet rs, int n) throws SQLException {
        return new OutboxRow(rs.getLong("id"), rs.getString("channel"), rs.getString("kind"),
                rs.getObject("recipient_account_id", Long.class), rs.getObject("issue_id", UUID.class),
                rs.getObject("comment_id", Long.class), rs.getInt("attempts"),
                instant(rs.getTimestamp("first_failed_at")), instant(rs.getTimestamp("created_at")));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
