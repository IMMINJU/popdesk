package io.popdesk.mailin;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.FailedWrite;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailInServer.Folder;
import io.popdesk.support.Unique;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Mail the app cannot handle (MAILIN-8): a database error is tried again in the next round, five times, then the mail is
 * quarantined; a mail over 30 MB or with over 200 MIME parts is quarantined at once.
 *
 * <p>The error is a {@link FailedWrite} on the issue row whose title is the mail's subject: the issue cannot be written,
 * and nothing else is touched.
 */
class FailedMailTest extends MailInAppTest {

    @Test
    @DisplayName("S12-AC-09 a mail whose issue cannot be written stays in the incoming folder and is counted as retrying; after five failed tries the sixth round quarantines it, with no issue")
    void fiveFailuresThenQuarantine() {
        Session sender = customer(company().code());
        Inbound mail = mailFrom(sender).subject(Unique.text("Fails")).text("Body " + Unique.number());
        int quarantinedBefore = rounds.mailIn().path("quarantined90d").asInt(-1);
        try (FailedWrite failing = FailedWrite.statement(db, "issue", "insert", mail.subject())) {
            send(mail);
            rounds.next();
            assertThat(foldersOf(mail)).as("folders after the first failed try").containsExactly(Folder.INBOUND);
            int retrying = rounds.mailIn().path("retrying").asInt(-1);
            assertThat(retrying).as("mailIn.retrying after the first try").isGreaterThanOrEqualTo(1);

            rounds.next(4);
            assertThat(foldersOf(mail)).as("folders after five failed tries").containsExactly(Folder.INBOUND);

            rounds.next();

            assertThat(foldersOf(mail)).as("folders after the sixth round").containsExactly(Folder.QUARANTINE);
            assertThat(rounds.mailIn().path("retrying").asInt(-1)).as("mailIn.retrying after the quarantine")
                    .isLessThan(retrying);
        }
        assertThat(issuesOf(sender)).as("issues of the sender").isEmpty();
        assertThat(rounds.mailIn().path("quarantined90d").asInt(-1)).as("mailIn.quarantined90d")
                .isGreaterThan(quarantinedBefore);
        rounds.next();
        assertThat(foldersOf(mail)).as("folders a round later").containsExactly(Folder.QUARANTINE);
        assertThat(issuesOf(sender)).as("issues of the sender a round later").isEmpty();
    }

    @Test
    @DisplayName("S12-AC-09 a mail over 30 MB is quarantined in its first round, with no issue")
    void tooLarge() {
        Session sender = customer(company().code());
        byte[] file = new byte[30 * 1024 * 1024 + 1];
        Arrays.fill(file, (byte) 'x');
        Inbound mail = mailFrom(sender).text("Large " + Unique.number()).attach("large.txt", "text/plain", file);
        try {
            send(mail);

            rounds.next();

            assertThat(foldersOf(mail)).as("folders of the large mail").containsExactly(Folder.QUARANTINE);
            assertThat(issuesOf(sender)).as("issues of the sender").isEmpty();
        } finally {
            mailIn().remove(mail.messageId());
        }
    }

    @Test
    @DisplayName("S12-AC-09 a mail with 201 MIME parts is quarantined in its first round, with no issue")
    void tooManyParts() {
        Session sender = customer(company().code());
        Inbound mail = mailFrom(sender).text("Parts " + Unique.number());
        for (int i = 0; i < 200; i++) {
            mail.unnamed("text/plain", ("part " + i).getBytes(StandardCharsets.US_ASCII));
        }
        send(mail);

        rounds.next();

        assertThat(foldersOf(mail)).as("folders of the mail with 201 parts").containsExactly(Folder.QUARANTINE);
        assertThat(issuesOf(sender)).as("issues of the sender").isEmpty();
    }
}
