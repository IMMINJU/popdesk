package io.popdesk.mailin;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailInServer.Folder;
import io.popdesk.support.Unique;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A mail that comes twice is handled once (MAILIN-6): the key is the {@code From} address and the {@code Message-ID}, or
 * the raw bytes without one. A copy discarded for {@code DMARC_FAIL} does not block the real mail after it.
 */
class DuplicateMailTest extends MailInAppTest {

    @Test
    @DisplayName("S12-AC-08 two mails with the same Message-ID from the same From give one issue, and both go to the processed folder")
    void sameMessageIdSameFrom() {
        Session sender = customer(company().code());
        String messageId = Inbound.newMessageId();
        send(mailFrom(sender).messageId(messageId).text("First " + Unique.number()));
        send(mailFrom(sender).messageId(messageId).text("Sent again " + Unique.number()));

        rounds.next();

        assertThat(issuesOf(sender)).as("issues of the sender").hasSize(1);
        assertThat(mailIn().withMessageId(Folder.PROCESSED, messageId)).as("mails with %s in the processed folder",
                messageId).hasSize(2);
        assertThat(mailIn().withMessageId(Folder.INBOUND, messageId)).as("in the incoming folder").isEmpty();
    }

    @Test
    @DisplayName("S12-AC-08 the same Message-ID sent by another From is handled on its own: each sender gets an issue")
    void sameMessageIdOtherFrom() {
        Session first = customer(company().code());
        Session second = customer(company().code());
        String messageId = Inbound.newMessageId();
        send(mailFrom(first).messageId(messageId).text("From the first " + Unique.number()));
        send(mailFrom(second).messageId(messageId).text("From the second " + Unique.number()));

        rounds.next();

        assertThat(issuesOf(first)).as("issues of the first sender").hasSize(1);
        assertThat(issuesOf(second)).as("issues of the second sender").hasSize(1);
        assertThat(mailIn().withMessageId(Folder.PROCESSED, messageId)).as("in the processed folder").hasSize(2);
    }

    @Test
    @DisplayName("S12-AC-08 two mails without a Message-ID and with the same bytes give one issue, and both go to the processed folder")
    void sameBytesWithoutMessageId() {
        Session sender = customer(company().code());
        Inbound mail = mailFrom(sender).noMessageId().text("No id " + Unique.number());
        byte[] raw = mail.bytes();
        send(raw);
        send(raw);

        rounds.next();

        assertThat(issuesOf(sender)).as("issues of the sender").hasSize(1);
        assertThat(mailIn().withSubject(Folder.PROCESSED, mail.subject())).as("in the processed folder").hasSize(2);
        assertThat(mailIn().withSubject(Folder.INBOUND, mail.subject())).as("in the incoming folder").isEmpty();
    }

    @Test
    @DisplayName("S12-AC-08 after a mail discarded for a DMARC fail, a mail with the same key that passes DMARC becomes an issue")
    void dmarcFailDoesNotBlockTheKey() {
        Session sender = customer(company().code());
        String messageId = Inbound.newMessageId();
        send(mailFrom(sender).messageId(messageId).text("Forged copy " + Unique.number())
                .header("Authentication-Results", "mx.example; dmarc=fail (p=reject) header.from=example.test"));
        rounds.next();
        assertThat(issuesOf(sender)).as("issues after the copy that failed DMARC").isEmpty();
        assertThat(mailIn().withMessageId(Folder.PROCESSED, messageId)).as("the copy in the processed folder")
                .hasSize(1);

        send(mailFrom(sender).messageId(messageId).text("Real mail " + Unique.number())
                .header("Authentication-Results", "mx.example; dmarc=pass header.from=example.test"));
        rounds.next();

        assertThat(issuesOf(sender)).as("issues after the real mail").hasSize(1);
        assertThat(mailIn().withMessageId(Folder.PROCESSED, messageId)).as("both in the processed folder").hasSize(2);
    }
}
