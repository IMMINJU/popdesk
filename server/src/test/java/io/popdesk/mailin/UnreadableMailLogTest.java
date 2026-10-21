package io.popdesk.mailin;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.AppLog;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailInServer;
import io.popdesk.support.MailInServer.Answer;
import io.popdesk.support.MailInServer.Folder;
import io.popdesk.support.MailInServer.Request;
import io.popdesk.support.Unique;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * What the log keeps of a mail the app could not read (B7-AC-15, PII-2): a warning with its UID in each round, and
 * nothing of its subject, sender, {@code Message-ID}, or the server's answer.
 */
class UnreadableMailLogTest extends MailInAppTest {

    /** What the server refuses. */
    enum Refused {
        /** The raw bytes, with {@code NO}. */
        RAW_NO,
        /** The raw bytes, with {@code BAD}. */
        RAW_BAD,
        /** The raw bytes and the header, with {@code NO}. */
        RAW_AND_HEADER_NO
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Refused.class)
    @DisplayName("B7-AC-15 a skipped mail gives a warning with its UID in each of two rounds, and no log line holds its subject, its sender's address, its Message-ID, or the server's answer text")
    void warningWithTheUidOnly(Refused refused) {
        Session sender = customer(company().code());
        String subject = Unique.text("Secret subject");
        Inbound mail = mailFrom(sender).subject(subject).text("Body " + Unique.number());
        String bareId = mail.messageId().replaceAll("^<|>$", "");
        try {
            mailIn().refuse(mail.messageId(), Request.FIRST_PART, refused == Refused.RAW_BAD ? Answer.BAD : Answer.NO);
            if (refused == Refused.RAW_AND_HEADER_NO) {
                mailIn().refuse(mail.messageId(), Request.HEADER, Answer.NO);
            }
            pushUidsPast(100);
            send(mail);
            long uid = mailIn().withMessageId(Folder.INBOUND, mail.messageId()).getFirst().uid();
            Pattern uidWord = Pattern.compile("\\b" + uid + "\\b");

            for (int round = 1; round <= 2; round++) {
                AppLog.Mark mark = log.mark();

                rounds.next();

                List<String> warnings = log.jsonLines(mark).stream()
                        .filter(l -> "WARN".equals(l.path("level").asString(null)))
                        .map(l -> l.path("message").asString("")).toList();
                assertThat(warnings).as("warnings of round %d with the UID %d", round, uid)
                        .anyMatch(m -> uidWord.matcher(m).find());
                List<String> lines = log.lines(mark);
                for (String secret : List.of(subject, bareId, sender.email(), MailInServer.REFUSAL_TEXT)) {
                    assertThat(lines).as("log lines of round %d with %s", round, secret)
                            .noneMatch(line -> line.contains(secret));
                }
            }
        } finally {
            mailIn().answerAll();
            mailIn().remove(mail.messageId());
        }
    }

    /**
     * Puts mail into the incoming folder and takes it out again until its next UID is over {@code floor}, so the UID
     * of the test's mail is a number no other word of a warning is likely to be. No round runs meanwhile: rounds wait
     * for the test to move the clock.
     */
    private void pushUidsPast(long floor) {
        String marker = Unique.text("Uid filler");
        byte[] filler = Inbound.mail().from("filler@mail.example").subject(marker).text("Filler").bytes();
        long uid = 0;
        while (uid < floor) {
            send(filler);
            uid = mailIn().withSubject(Folder.INBOUND, marker).getFirst().uid();
            mailIn().removeWithSubject(marker);
        }
    }
}
