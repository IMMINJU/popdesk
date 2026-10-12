package io.popdesk.notify;

import static io.popdesk.issue.TalkFlow.assertRefused;
import static io.popdesk.issue.TalkFlow.isNull;
import static io.popdesk.notify.DeliverySteps.TEST_MAIL_SENT;
import static io.popdesk.notify.DeliverySteps.auditEntries;
import static io.popdesk.notify.NotifySteps.hasHangul;
import static io.popdesk.notify.NotifySteps.notifications;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.AuditChainMath;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailServer;
import io.popdesk.support.ReceivedMail;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * The test mail (OPS-3): {@code POST /api/console/system/test-mail} sends one mail to the caller's own
 * address within the request and says what the SMTP server answered.
 *
 * <p>The limit is 5 a minute for one admin and the clock stands still, so each test has an admin of
 * its own. A refusal comes from {@link Delivery.FakeSmtp} with a text of the test's own; what GreenMail
 * says by itself is not fixed here.
 */
class TestMailTest extends Delivery.PlainApp {

    /** The console address of this setup, as the mail gives it. */
    private static final String CONSOLE = "http://" + CONSOLE_HOST;

    /** The time a mail was sent, as the mail gives it: minutes, in the installation's time zone (UTC here). */
    private static final DateTimeFormatter SENT_AT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private Session tester() {
        return person(admin(), Role.ADMIN, "Tester");
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private long queuedMailRows() {
        return db.count("select count(*) from outbox where channel = 'MAIL'");
    }

    /** {@code serverReply} of a result that failed, as text. */
    private static String replyOfAFailure(JsonNode result) {
        assertThat(result.path("ok").isBoolean() && !result.path("ok").booleanValue()).as("ok is false in %s", result)
                .isTrue();
        assertThat(result.path("errorKind").asString("")).as("errorKind in %s", result).matches("[A-Z][A-Z0-9_]*");
        assertThat(result.path("serverReply").isString()).as("serverReply is a text in %s", result).isTrue();
        return result.path("serverReply").stringValue();
    }

    private static void assertSent(JsonNode result) {
        assertThat(result.path("ok").isBoolean() && result.path("ok").booleanValue()).as("ok is true in %s", result)
                .isTrue();
        assertThat(isNull(result, "errorKind")).as("errorKind is null in %s", result).isTrue();
    }

    // S4B-AC-22

    @Test
    @DisplayName("S4B-AC-22 an admin's test mail is at their own address when the answer comes: 200 with ok, one "
            + "mail, none to another admin, and no row in the send queue")
    void testMailGoesToTheCallerWithinTheRequest() {
        Session admin = admin();
        Session tester = person(admin, Role.ADMIN, "Tester");
        Session other = person(admin, Role.ADMIN, "Other");
        long queued = queuedMailRows();

        JsonNode result = Delivery.testMail(tester);

        assertSent(result);
        List<ReceivedMail> got = notifications(mail(), tester.email());
        assertThat(got).as("mails at the admin's address when the answer came").hasSize(1);
        assertThat(got.getFirst().to()).as("To").containsExactly(tester.email());
        assertThat(got.getFirst().cc()).as("Cc").isEmpty();
        assertThat(queuedMailRows()).as("mail rows in the send queue").isLessThanOrEqualTo(queued);
        awaitALaterMail(admin);
        assertThat(notifications(mail(), tester.email())).as("mails to the admin after a later mail arrived")
                .hasSize(1);
        assertThat(notifications(mail(), other.email())).as("mails to another admin").isEmpty();
        assertThat(notifications(mail(), ADMIN_EMAIL)).as("mails to the first admin").isEmpty();
    }

    // S4B-AC-23

    @ParameterizedTest(name = "refused at {0}")
    @CsvSource({"AUTH,535 5.7.8", "RCPT TO:,550 5.1.1", "<end of data>,554 5.6.0"})
    @DisplayName("S4B-AC-23 when the SMTP server refuses the sign-in, the recipient, or the message, the answer is "
            + "200 with ok false, the kind of the error, and the server's reply; nothing is queued")
    void refusedTestMailSaysWhatTheServerAnswered(String command, String code) {
        Session tester = tester();
        String word = IssueFlow.word();
        long queued = queuedMailRows();
        smtp().refuse(command, code + " Refused " + word);

        JsonNode result = Delivery.testMail(tester);

        assertThat(replyOfAFailure(result)).as("serverReply").contains(word);
        assertThat(notifications(mail(), tester.email())).as("mails to the admin").isEmpty();
        assertThat(queuedMailRows()).as("mail rows in the send queue").isLessThanOrEqualTo(queued);
    }

    @Test
    @DisplayName("S4B-AC-23 when the SMTP server cannot be talked to, the answer is 200 with ok false, the kind "
            + "of the error, and null for the server's reply")
    void serverThatGoesAwayIsAFailureWithoutAReply() {
        Session tester = tester();
        smtp().drop();

        JsonNode result = Delivery.testMail(tester);

        assertThat(result.path("ok").isBoolean() && !result.path("ok").booleanValue()).as("ok is false in %s", result)
                .isTrue();
        assertThat(result.path("errorKind").asString("")).as("errorKind in %s", result).matches("[A-Z][A-Z0-9_]*");
        assertThat(isNull(result, "serverReply")).as("serverReply is null in %s", result).isTrue();
    }

    // S4B-AC-24

    @Test
    @DisplayName("S4B-AC-24 a reply that repeats the SMTP user name and password, as they are and as Base64, "
            + "comes back without them")
    void replyComesBackWithoutTheSmtpUserAndPassword() {
        Session tester = tester();
        String word = IssueFlow.word();
        String both = base64("\0" + MailServer.USER + "\0" + MailServer.PASSWORD);
        smtp().script(line -> line.toUpperCase(Locale.ROOT).startsWith("AUTH")
                ? "535 5.7.8 " + word + " user " + MailServer.USER + " password " + MailServer.PASSWORD + " "
                        + base64(MailServer.USER) + " " + base64(MailServer.PASSWORD) + " " + both + " "
                        + line.substring(line.lastIndexOf(' ') + 1)
                : null);

        ApiResponse response = Delivery.testMailRequest(tester).expect(200);

        assertThat(replyOfAFailure(response.json())).as("serverReply").contains(word);
        for (String secret : List.of(MailServer.USER, MailServer.PASSWORD, base64(MailServer.USER),
                base64(MailServer.PASSWORD), both)) {
            assertThat(Delivery.foundIn(response.text(), secret)).as("forms of %s in the answer %s", secret,
                    response.text()).isEmpty();
        }
    }

    @Test
    @DisplayName("S4B-AC-24 a reply that repeats the recipient's address comes back with the address masked after "
            + "its first character")
    void replyComesBackWithTheAddressMasked() {
        Session tester = tester();
        String word = IssueFlow.word();
        smtp().script(line -> line.toUpperCase(Locale.ROOT).startsWith("RCPT TO:")
                ? "550 5.1.1 " + line.substring(8).strip() + " unknown " + word : null);

        ApiResponse response = Delivery.testMailRequest(tester).expect(200);

        String reply = replyOfAFailure(response.json());
        assertThat(reply).as("serverReply").contains(word);
        assertThat(Delivery.foundIn(response.text(), tester.email())).as("forms of the address in the answer %s",
                response.text()).isEmpty();
        assertThat(Delivery.maskedLines(List.of(reply), tester.email().charAt(0))).as("the masked address in %s",
                reply).isNotEmpty();
    }

    @Test
    @DisplayName("S4B-AC-24 a reply a little over 500 characters comes back cut at 500")
    void longReplyIsCut() {
        Session tester = tester();
        String word = IssueFlow.word();
        smtp().refuseMessage("554 5.6.0 " + word + " " + "x".repeat(510));

        String reply = replyOfAFailure(Delivery.testMail(tester));

        assertThat(reply).as("serverReply").contains(word).hasSizeBetween(400, 500);
    }

    @Test
    @DisplayName("S4B-AC-24 a reply of two lines with a bell and an escape character comes back with its line "
            + "break and without the other control characters")
    void controlCharactersAreTakenOutAndLineBreaksKept() {
        Session tester = tester();
        String first = IssueFlow.word();
        String second = IssueFlow.word();
        smtp().refuseMessage("554-5.6.0 " + first + " bell\u0007here\r\n554 5.6.0 esc\u001b[31m " + second);

        String reply = replyOfAFailure(Delivery.testMail(tester));

        assertThat(reply).as("serverReply").contains(first).contains(second);
        assertThat(reply.chars().filter(ch -> Character.isISOControl(ch) && ch != '\n' && ch != '\r').boxed()
                .toList()).as("control characters other than line breaks in %s", reply).isEmpty();
        assertThat(reply.substring(reply.indexOf(first), reply.indexOf(second))).as("between the two lines")
                .contains("\n");
    }

    // S4B-AC-26

    @Test
    @DisplayName("S4B-AC-26 S4B-AC-27 the sixth test mail of an admin within 60 seconds is 429 with Retry-After, "
            + "sends nothing, and leaves no audit entry; another admin is not limited, and after 60 seconds the "
            + "admin can send again")
    void sixthTestMailInAMinuteIsLimited() {
        Session admin = admin();
        Session tester = person(admin, Role.ADMIN, "Tester");
        Session other = person(admin, Role.ADMIN, "Other");
        for (int i = 0; i < 5; i++) {
            assertSent(Delivery.testMail(tester));
        }

        ApiResponse sixth = Delivery.testMailRequest(tester).send();

        assertRefused(sixth, 429, "RATE_LIMITED");
        assertThat(sixth.header("Retry-After")).as("Retry-After").isNotNull().matches("[1-9][0-9]?");
        assertThat(Integer.parseInt(sixth.header("Retry-After"))).as("Retry-After, seconds left of the window")
                .isBetween(1, 60);
        assertThat(notifications(mail(), tester.email())).as("mails to the limited admin").hasSize(5);
        assertThat(auditEntries(tester, TEST_MAIL_SENT, tester.accountId())).as("audit entries of the limited admin")
                .hasSize(5);
        assertSent(Delivery.testMail(other));
        clock.advance(Duration.ofSeconds(61));
        assertSent(Delivery.testMail(tester));
    }

    // S4B-AC-27

    @Test
    @DisplayName("S4B-AC-27 a test mail leaves a TEST_MAIL_SENT entry whether it worked or not: the admin who "
            + "sent it, ok, and the kind of the error when it failed, without the address and the server's reply; "
            + "an agent's 403 leaves none, and the integrity check still passes")
    void testMailIsAuditedWithItsResultOnly() {
        Session admin = admin();
        Session tester = person(admin, Role.ADMIN, "Tester");
        Session agent = person(admin, Role.AGENT, "Agent");
        String word = IssueFlow.word();

        assertSent(Delivery.testMail(tester));
        smtp().script(line -> line.toUpperCase(Locale.ROOT).startsWith("RCPT TO:")
                ? "550 5.1.1 " + line.substring(8).strip() + " unknown " + word : null);
        JsonNode refused = Delivery.testMail(tester);
        assertThat(replyOfAFailure(refused)).as("serverReply").contains(word);
        assertRefused(Delivery.testMailRequest(agent).send(), 403, "FORBIDDEN");

        List<JsonNode> entries = auditEntries(tester, TEST_MAIL_SENT, tester.accountId());
        assertThat(entries).as("TEST_MAIL_SENT entries of the admin, newest first").hasSize(2);
        JsonNode failure = entries.get(0);
        JsonNode success = entries.get(1);
        for (JsonNode entry : entries) {
            DeliverySteps.assertActor(entry, tester);
            assertThat(entry.path("changes").path("ok").isBoolean()).as("changes.ok is a boolean in %s", entry)
                    .isTrue();
            assertThat(entry.toString()).as("the audit entry").doesNotContainIgnoringCase(word);
            // The actor is the admin, whose own address the mail went to; the rest of the entry holds none.
            String rest = entry.path("changes") + " " + entry.path("target");
            assertThat(Delivery.foundIn(rest, tester.email())).as("the address in the changes and the target %s",
                    rest).isEmpty();
        }
        assertThat(success.path("changes").path("ok").booleanValue()).as("ok of the mail that went out").isTrue();
        assertThat(success.path("changes").path("errorKind").isMissingNode()
                || success.path("changes").path("errorKind").isNull()).as("no errorKind in %s", success).isTrue();
        assertThat(failure.path("changes").path("ok").booleanValue()).as("ok of the refused mail").isFalse();
        assertThat(failure.path("changes").path("errorKind").asString(null)).as("errorKind of the refused mail")
                .isEqualTo(refused.path("errorKind").asString(""));
        assertThat(auditEntries(tester, TEST_MAIL_SENT, agent.accountId())).as("entries of the agent's 403").isEmpty();
        AuditChainMath.assertValid(tester.client());
    }

    // S4B-AC-28

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"en", "ko"})
    @DisplayName("S4B-AC-28 the test mail is in the recipient's language, with the console address and the time it "
            + "was sent: the snapshot text")
    void testMailInTheRecipientsLanguage(String language) {
        Session tester = tester();
        tester.client().patch("/api/console/me").json(Map.of("locale", language)).expect(200);
        Instant now = clock.instant();

        assertSent(Delivery.testMail(tester));

        List<ReceivedMail> got = notifications(mail(), tester.email());
        assertThat(got).as("test mails to the admin").hasSize(1);
        ReceivedMail m = got.getFirst();
        String time = SENT_AT.format(now) + " (UTC)";
        assertThat(m.text()).as("text of the mail").contains(CONSOLE).contains(time).doesNotContain(PORTAL_HOST);
        assertThat(m.subject()).as("subject").doesNotContain("\n").doesNotContain("\r");
        assertThat(hasHangul(m.subject() + m.text())).as("Korean in the %s mail: %s\n%s", language, m.subject(),
                m.text()).isEqualTo(language.equals("ko"));
        String text = m.text().replace("\r\n", "\n").replace(CONSOLE, "{link}").replace(time, "{time}")
                .stripTrailing();
        WebhookSnapshots.assertMatches(language + "-mail-test.txt", "Subject: " + m.subject() + "\n\n" + text);
    }
}
