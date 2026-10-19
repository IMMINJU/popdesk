package io.popdesk.helpdesk.mailin;

import java.sql.Timestamp;
import java.time.Instant;
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

    private final JdbcClient jdbc;

    MailReceivedRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
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
}
