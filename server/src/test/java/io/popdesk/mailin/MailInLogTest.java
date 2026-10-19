package io.popdesk.mailin;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.AppLog;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailInServer;
import io.popdesk.support.Unique;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the log keeps of a received mail (PII-2): its record, outcome, and reason, nothing of its content. */
class MailInLogTest extends MailInAppTest {

    @Test
    @DisplayName("S12-AC-34 while a mail becomes an issue the app's log holds a line with its outcome, and none of its subject, body, file name, Message-ID, the sender's address, or the IMAP password")
    void logKeepsNoContent() {
        Session sender = customer(company().code());
        String subject = Unique.text("Secret subject");
        String body = "Secret body " + Unique.number();
        String fileName = "secret-file-" + Unique.number() + ".txt";
        Inbound mail = mailFrom(sender).subject(subject).text(body)
                .attach(fileName, "text/plain", "file".getBytes(StandardCharsets.US_ASCII));
        String bareId = mail.messageId().replaceAll("^<|>$", "");
        AppLog.Mark mark = log.mark();

        send(mail);
        rounds.next();

        assertThat(issuesOf(sender)).as("issues of the sender").hasSize(1);
        List<String> lines = log.lines(mark);
        assertThat(lines).as("log lines of the round").anyMatch(line -> line.contains("ISSUE"));
        for (String secret : List.of(subject, body, fileName, bareId, sender.email(), MailInServer.IMAP_PASSWORD)) {
            assertThat(lines).as("log lines with %s", secret).noneMatch(line -> line.contains(secret));
        }
    }
}
