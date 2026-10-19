package io.popdesk.notification.outbox;

import io.popdesk.notification.SentMailLookup;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@code Message-ID}s of the email sent to customers about an issue (table {@code mail_sent}, MAIL-7), without
 * the angle brackets, so that a reply finds its issue (MAILIN-2).
 */
@Repository
class MailSentRepository implements SentMailLookup {

    private final JdbcClient jdbc;

    MailSentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Records the sent email; nothing when its {@code Message-ID} is recorded already. */
    void insert(String messageId, UUID issueId, Instant sentAt) {
        jdbc.sql("insert into mail_sent (tenant_id, message_id, issue_id, sent_at) values (1, ?, ?, ?) "
                        + "on conflict (message_id) do nothing")
                .params(messageId, issueId, Timestamp.from(sentAt)).update();
    }

    @Override
    public Optional<UUID> issueOf(Collection<String> messageIds) {
        if (messageIds.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.sql("select issue_id from mail_sent where message_id in (:ids) "
                        + "order by sent_at desc, id desc limit 1")
                .param("ids", List.copyOf(messageIds)).query(UUID.class).optional();
    }

    @Override
    public int deleteAbout(UUID issueId) {
        return jdbc.sql("delete from mail_sent where issue_id = ?").param(issueId).update();
    }

    @Override
    public int deleteSentBefore(Instant before) {
        return jdbc.sql("delete from mail_sent where sent_at < ?").param(Timestamp.from(before)).update();
    }
}
