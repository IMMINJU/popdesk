package io.popdesk.helpdesk.mailin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The received mail records (table {@code mail_received}, MAILIN-6): one row per email by its key, with the attempts,
 * the outcome and why, and the issue or comment it became.
 */
@Repository
public class MailReceivedRepository {

    /** The outcomes that count as quarantined (design.md 0절 15 ④). */
    static final String QUARANTINED = "'QUARANTINED', 'RATE_LIMITED'";

    /** A record as a round reads it before it handles the email. {@code outcome} and {@code reason} are null while open. */
    record Received(long id, int attempts, String outcome, String reason) {}

    private final JdbcClient jdbc;

    MailReceivedRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The record of the email with this key, if there is one. */
    Optional<Received> find(String messageKey) {
        return jdbc.sql("select id, attempts, outcome, reason from mail_received where message_key = ?")
                .param(messageKey).query(MailReceivedRepository::received).optional();
    }

    /**
     * Counts one more try at the email, in a transaction of its own before it is handled (S12-AC-09): a new record
     * with one try, or one more on a record without an outcome. A record discarded for {@code DMARC_FAIL} starts
     * again from one try without its outcome, so the email after it is handled as if it came first (S12-AC-08).
     * Another record with an outcome is left as it is.
     *
     * @param keySource {@code MESSAGE_ID} or {@code CONTENT}
     * @return the record after the count; empty when it has an outcome other than {@code DMARC_FAIL}
     */
    Optional<Received> attempt(String messageKey, String keySource, Instant now) {
        return jdbc.sql("insert into mail_received (tenant_id, message_key, key_source, attempts, first_seen_at) "
                        + "values (1, ?, ?, 1, ?) on conflict (message_key) do update set "
                        + "attempts = case when mail_received.outcome is null then mail_received.attempts + 1 else 1 end, "
                        + "outcome = null, reason = null, decided_at = null, issue_id = null, comment_id = null, "
                        + "sender_account_id = null, sender_address = null "
                        + "where mail_received.outcome is null "
                        + "or (mail_received.outcome = 'DISCARDED' and mail_received.reason = 'DMARC_FAIL') "
                        + "returning id, attempts, outcome, reason")
                .params(messageKey, keySource, Timestamp.from(now))
                .query(MailReceivedRepository::received).optional();
    }

    /**
     * Writes the outcome of an open record, in the caller's transaction: the one that made the issue or the comment,
     * when it did (S12-AC-30). The caller gives a sender only for an issue, a comment, or a notice; a discarded email
     * keeps none (S12-AC-14).
     *
     * @throws IllegalStateException when the record has an outcome already, so the caller's transaction rolls back
     */
    void decide(long id, String outcome, String reason, UUID issueId, Long commentId, Long senderAccountId,
            String senderAddress, Instant now) {
        int updated = jdbc.sql("update mail_received set outcome = ?, reason = ?, issue_id = ?, comment_id = ?, "
                        + "sender_account_id = ?, sender_address = ?, decided_at = ? where id = ? and outcome is null")
                .params(outcome, reason, issueId, commentId, senderAccountId, senderAddress, Timestamp.from(now), id)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("The received-mail record " + id + " has an outcome already");
        }
    }

    /** Records with no outcome and at least one attempt: emails left in the inbox folder to try again. */
    int countRetrying() {
        return jdbc.sql("select count(*) from mail_received where outcome is null and attempts >= 1")
                .query(Integer.class).single();
    }

    /** Records quarantined ({@code QUARANTINED} or {@code RATE_LIMITED}) at or after {@code since}. */
    int countQuarantinedSince(Instant since) {
        return jdbc.sql("select count(*) from mail_received where outcome in (" + QUARANTINED + ") "
                        + "and decided_at >= ?")
                .param(Timestamp.from(since)).query(Integer.class).single();
    }

    /** The time of the latest quarantine ({@code QUARANTINED} or {@code RATE_LIMITED}), if any. */
    Optional<Instant> lastQuarantinedAt() {
        return jdbc.sql("select max(decided_at) from mail_received where outcome in (" + QUARANTINED + ")")
                .query((rs, n) -> Optional.ofNullable(rs.getTimestamp(1)).map(Timestamp::toInstant)).single();
    }

    /**
     * Takes the issue out of every record that points at it, in the caller's transaction: the issue is purged, and
     * its records keep no link to it (design.md 0절 12). The comments' links go with the comments ({@code ON DELETE
     * SET NULL}).
     *
     * @return how many records pointed at it
     */
    public int clearIssue(UUID issueId) {
        return jdbc.sql("update mail_received set issue_id = null where issue_id = ?").param(issueId).update();
    }

    /**
     * Clears the sender address of the records of the email the account sent, in the caller's transaction: it is
     * being anonymized (S12-AC-31). The account ID stays; it names nobody once the account is anonymous.
     *
     * @return how many records had an address
     */
    public int clearSender(long accountId) {
        return jdbc.sql("update mail_received set sender_address = null where sender_account_id = ? "
                        + "and sender_address is not null")
                .param(accountId).update();
    }

    /** Deletes the records first seen before {@code before}, open ones too (S12-AC-33). Returns how many. */
    int deleteSeenBefore(Instant before) {
        return jdbc.sql("delete from mail_received where first_seen_at < ?").param(Timestamp.from(before)).update();
    }

    private static Received received(ResultSet rs, int n) throws SQLException {
        return new Received(rs.getLong("id"), rs.getInt("attempts"), rs.getString("outcome"), rs.getString("reason"));
    }
}
