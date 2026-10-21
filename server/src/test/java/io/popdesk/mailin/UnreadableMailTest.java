package io.popdesk.mailin;

import static io.popdesk.notify.DeliverySteps.awaitItems;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.TalkFlow;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailInServer.Answer;
import io.popdesk.support.MailInServer.Folder;
import io.popdesk.support.MailInServer.Request;
import io.popdesk.support.Unique;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * A mail the app cannot read while the connection is up (b7 acceptance.md 2절 메일 받기): the IMAP server answers the
 * request for its raw bytes, or its header too, with {@code NO} or {@code BAD} ({@code MailInServer.refuse}). The mail
 * is skipped and stays in the incoming folder, its try is counted by the key of its header, and after five it is
 * quarantined; the round goes on with the next mail and is a good one. A connection that goes away while a mail is read
 * still fails the round.
 *
 * <p>Every test sets its refusals on its own {@code Message-ID} and ends them, and takes its mail out of the mailbox, at
 * its end.
 */
class UnreadableMailTest extends MailInAppTest {

    static final String STALLED = "MAIL_IN_STALLED";
    static final String QUARANTINED = "MAIL_IN_QUARANTINED";

    /** The three ways of B7-AC-10 to not get a mail's raw bytes. */
    enum Unreadable {
        FIRST_PART_NO(Request.FIRST_PART, Answer.NO),
        SECOND_PART_NO(Request.SECOND_PART, Answer.NO),
        FIRST_PART_BAD(Request.FIRST_PART, Answer.BAD);

        final Request request;
        final Answer answer;

        Unreadable(Request request, Answer answer) {
            this.request = request;
            this.answer = answer;
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Unreadable.class)
    @DisplayName("B7-AC-10 a mail whose raw bytes the server refuses (NO for the first part, NO for the second part of a mail over 1 MB, BAD for the first part) stays in the incoming folder with no issue, no mail to its sender, and no decided record; a customer mail after it becomes an issue in the same round, which is a good one")
    void unreadableMailIsSkipped(Unreadable how) {
        Session sender = customer(company().code());
        Session next = customer(company().code());
        Inbound unreadable = mailFrom(sender).subject(Unique.text("Unreadable")).text("Body " + Unique.number());
        byte[] raw = unreadable.bytes();
        if (how == Unreadable.SECOND_PART_NO) {
            byte[] file = new byte[1536 * 1024];
            Arrays.fill(file, (byte) 'x');
            raw = cutAtALineEnd(unreadable.attach("large.txt", "text/plain", file).bytes());
        }
        Inbound after = mailFrom(next).subject(Unique.text("After")).text("Next " + Unique.number());
        try {
            mailIn().refuse(unreadable.messageId(), how.request, how.answer);
            send(raw);
            send(after);

            // The round is a good one: next() waits for a lastSuccessAt at or after the moved clock.
            rounds.next();

            assertThat(foldersOf(unreadable)).as("folders of the unreadable mail").containsExactly(Folder.INBOUND);
            assertThat(issuesOf(sender)).as("issues of the unreadable mail's sender").isEmpty();
            assertThat(foldersOf(after)).as("folders of the mail after it").containsExactly(Folder.PROCESSED);
            assertThat(issuesOf(next)).as("issues of the next sender").hasSize(1);
            Map<String, Object> record = received(sender.email(), unreadable.messageId());
            if (record != null) {
                assertThat(record.get("outcome")).as("outcome of the record of the unreadable mail").isNull();
            }
            awaitALaterMail();
            assertThat(mailTo(sender)).as("mail to the unreadable mail's sender").isEmpty();
        } finally {
            mailIn().answerAll();
            mailIn().remove(unreadable.messageId());
        }
    }

    @Test
    @DisplayName("B7-AC-10 a good round with an unreadable mail (BAD for the first part) in front resolves MAIL_IN_STALLED: rounds failing for another hour give a second item")
    void unreadableMailResolvesStalled() {
        rounds.next();
        Session watcher = fixtures.accept(invite(Role.ADMIN));
        failForAnHour();
        awaitItems(fixtures.signInAgain(watcher), STALLED, 1, "an hour after the first failed round");
        Session sender = customer(company().code());
        Inbound unreadable = mailFrom(sender).text("Unreadable " + Unique.number());
        try {
            mailIn().refuse(unreadable.messageId(), Request.FIRST_PART, Answer.BAD);
            send(unreadable);

            rounds.next();

            assertThat(foldersOf(unreadable)).as("folders of the unreadable mail").containsExactly(Folder.INBOUND);
            failForAnHour();
            awaitItems(fixtures.signInAgain(watcher), STALLED, 2, "an hour after the rounds failed again");
        } finally {
            mailIn().answerAll();
            mailIn().remove(unreadable.messageId());
        }
        rounds.next();
    }

    @Test
    @DisplayName("B7-AC-11 a skipped mail with a Message-ID gets the record of its header's key with one try and no outcome, and stays in the incoming folder")
    void skippedMailIsCounted() {
        Session sender = customer(company().code());
        Inbound unreadable = mailFrom(sender).text("Counted " + Unique.number());
        try {
            mailIn().refuse(unreadable.messageId(), Request.FIRST_PART, Answer.NO);
            send(unreadable);

            rounds.next();

            assertThat(foldersOf(unreadable)).as("folders of the unreadable mail").containsExactly(Folder.INBOUND);
            Map<String, Object> record = received(sender.email(), unreadable.messageId());
            assertThat(record).as("record of the header's key").isNotNull();
            assertThat(((Number) record.get("attempts")).intValue()).as("attempts").isEqualTo(1);
            assertThat(record.get("outcome")).as("outcome").isNull();
            assertThat(record.get("key_source")).as("key_source").isEqualTo("MESSAGE_ID");
        } finally {
            mailIn().answerAll();
            mailIn().remove(unreadable.messageId());
        }
    }

    @Test
    @DisplayName("B7-AC-11 a skipped mail whose header's key already has an outcome (quarantined before) goes to the folder of that outcome, with no second record and no issue")
    void skippedMailWithAnOutcomeIsMoved() {
        Session sender = customer(company().code());
        String messageId = Inbound.newMessageId();
        Inbound original = tooComplex(sender).messageId(messageId);
        send(original);
        rounds.next();
        assertThat(mailIn().withMessageId(Folder.QUARANTINE, messageId)).as("the first copy quarantined").hasSize(1);
        Inbound copy = mailFrom(sender).messageId(messageId).text("Copy " + Unique.number());
        try {
            mailIn().refuse(messageId, Request.FIRST_PART, Answer.NO);
            send(copy);

            rounds.next();

            assertThat(mailIn().withMessageId(Folder.INBOUND, messageId)).as("copies in the incoming folder")
                    .isEmpty();
            assertThat(mailIn().withMessageId(Folder.QUARANTINE, messageId)).as("copies in the quarantine folder")
                    .hasSize(2);
            assertThat(receivedRows(sender.email(), messageId)).as("records of the key").hasSize(1);
            assertThat(issuesOf(sender)).as("issues of the sender").isEmpty();
        } finally {
            mailIn().answerAll();
            mailIn().remove(messageId);
        }
    }

    @ParameterizedTest(name = "readable in the sixth round: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("B7-AC-12 a mail skipped in five rounds is counted five times and stays; the sixth round quarantines it with no issue and gives a MAIL_IN_QUARANTINED item, its record QUARANTINED with the reason UNREADABLE, or FAILED when the sixth round could read it")
    void fiveSkipsThenQuarantine(boolean readableAtSixth) {
        // A day without quarantine resolves an alert an earlier test raised.
        passTime(Duration.ofHours(25));
        rounds.next();
        Session watcher = fixtures.accept(invite(Role.ADMIN));
        Session sender = customer(company().code());
        Inbound unreadable = mailFrom(sender).text("Five times " + Unique.number());
        try {
            mailIn().refuse(unreadable.messageId(), Request.FIRST_PART, Answer.NO);
            send(unreadable);
            rounds.next(5);
            assertThat(foldersOf(unreadable)).as("folders after five rounds").containsExactly(Folder.INBOUND);
            Map<String, Object> counted = received(sender.email(), unreadable.messageId());
            assertThat(counted).as("record after five rounds").isNotNull();
            assertThat(((Number) counted.get("attempts")).intValue()).as("attempts after five rounds").isEqualTo(5);
            assertThat(counted.get("outcome")).as("outcome after five rounds").isNull();
            if (readableAtSixth) {
                mailIn().answerAll();
            }

            rounds.next();

            assertThat(foldersOf(unreadable)).as("folders after the sixth round").containsExactly(Folder.QUARANTINE);
            Map<String, Object> record = received(sender.email(), unreadable.messageId());
            assertThat(record.get("outcome")).as("outcome").isEqualTo("QUARANTINED");
            assertThat(record.get("reason")).as("reason").isEqualTo(readableAtSixth ? "FAILED" : "UNREADABLE");
            assertThat(issuesOf(sender)).as("issues of the sender").isEmpty();
            JsonNode item = awaitItems(watcher, QUARANTINED, 1, "after the sixth round").getFirst();
            assertThat(TalkFlow.isNull(item, "issue")).as("issue is null in %s", item).isTrue();
        } finally {
            mailIn().answerAll();
            mailIn().remove(unreadable.messageId());
        }
    }

    @Test
    @DisplayName("B7-AC-13 a mail whose raw bytes and header the server both refuse leaves no record and stays for five rounds; the sixth round quarantines it and gives a MAIL_IN_QUARANTINED item, still with no record")
    void noHeaderEither() {
        passTime(Duration.ofHours(25));
        rounds.next();
        Session watcher = fixtures.accept(invite(Role.ADMIN));
        Session sender = customer(company().code());
        Inbound unreadable = mailFrom(sender).text("No header " + Unique.number());
        try {
            mailIn().refuse(unreadable.messageId(), Request.FIRST_PART, Answer.NO);
            mailIn().refuse(unreadable.messageId(), Request.HEADER, Answer.NO);
            send(unreadable);
            rounds.next();
            assertThat(foldersOf(unreadable)).as("folders after the first round").containsExactly(Folder.INBOUND);
            rounds.next(4);
            assertThat(foldersOf(unreadable)).as("folders after five rounds").containsExactly(Folder.INBOUND);

            rounds.next();

            assertThat(foldersOf(unreadable)).as("folders after the sixth round").containsExactly(Folder.QUARANTINE);
            assertThat(receivedRows(sender.email(), unreadable.messageId())).as("records of the mail").isEmpty();
            assertThat(issuesOf(sender)).as("issues of the sender").isEmpty();
            JsonNode item = awaitItems(watcher, QUARANTINED, 1, "after the sixth round").getFirst();
            assertThat(TalkFlow.isNull(item, "issue")).as("issue is null in %s", item).isTrue();
        } finally {
            mailIn().answerAll();
            mailIn().remove(unreadable.messageId());
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Request.class, names = {"FIRST_PART", "HEADER"})
    @DisplayName("B7-AC-14 a connection that goes away while the raw bytes (or, after a NO for them, the header) of a mail are read fails the round with CONNECT and counts no try; once it stays up the next round makes the issue with one try")
    void connectionGoneWhileReading(Request dropped) {
        Session sender = customer(company().code());
        Inbound mail = mailFrom(sender).text("Dropped " + Unique.number());
        try {
            if (dropped == Request.HEADER) {
                mailIn().refuse(mail.messageId(), Request.FIRST_PART, Answer.NO);
            }
            mailIn().refuse(mail.messageId(), dropped, Answer.DROP);
            send(mail);

            String kind = rounds.nextFailing();

            assertThat(kind).as("mailIn.lastFailure.kind").isEqualTo("CONNECT");
            assertThat(foldersOf(mail)).as("folders after the failed round").containsExactly(Folder.INBOUND);
            assertThat(receivedRows(sender.email(), mail.messageId())).as("records after the failed round").isEmpty();
        } finally {
            mailIn().answerAll();
        }
        rounds.next();
        assertThat(foldersOf(mail)).as("folders after a good round").containsExactly(Folder.PROCESSED);
        assertThat(issuesOf(sender)).as("issues of the sender").hasSize(1);
        Map<String, Object> record = received(sender.email(), mail.messageId());
        assertThat(((Number) record.get("attempts")).intValue()).as("attempts").isEqualTo(1);
    }

    /** Rounds fail ({@code cut}) from now until over an hour after the first failed one; the mailbox is back after. */
    private void failForAnHour() {
        mailIn().cut();
        try {
            rounds.nextFailing();
            clock.advance(Duration.ofMinutes(61));
            rounds = new Rounds(admin = fixtures.signInAgain(admin), clock);
            rounds.nextFailing();
        } finally {
            mailIn().restore();
        }
    }

    /**
     * The raw mail with an {@code X-Pad} header in front, so the first part of 1 MB ends at the end of a base64 line of
     * its last part. Cut there, the mail still reads as a whole mail with a shorter file, which is what the app must
     * not take for the mail (B7-AC-10); cut inside a line, the base64 would not decode and the app would fail anyway.
     */
    private static byte[] cutAtALineEnd(byte[] raw) {
        byte[] marker = "Content-Transfer-Encoding: base64\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        String text = new String(raw, StandardCharsets.ISO_8859_1);
        int start = text.lastIndexOf(new String(marker, StandardCharsets.ISO_8859_1)) + marker.length;
        int line = 76 + 2;
        int shift = Math.floorMod(1024 * 1024 - start, line);
        if (shift < "X-Pad: \r\n".length()) {
            shift += line;
        }
        byte[] pad = ("X-Pad: " + "a".repeat(shift - "X-Pad: \r\n".length()) + "\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[pad.length + raw.length];
        System.arraycopy(pad, 0, out, 0, pad.length);
        System.arraycopy(raw, 0, out, pad.length, raw.length);
        return out;
    }

    /** A mail with 201 MIME parts, which is quarantined (S12-AC-09). */
    private static Inbound tooComplex(Session sender) {
        Inbound mail = mailFrom(sender).text("Parts " + Unique.number());
        for (int i = 0; i < 200; i++) {
            mail.unnamed("text/plain", ("part " + i).getBytes(StandardCharsets.US_ASCII));
        }
        return mail;
    }
}
