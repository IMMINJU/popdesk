package io.popdesk.notify;

import static io.popdesk.notify.Delivery.MAIL_LIMIT;
import static io.popdesk.notify.NotifySteps.notifications;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.TalkFlow;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * At most 50 mails an hour to one address (MAIL-6). The mails are real: a customer files one issue and
 * two agents answer it with public comments, each comment one mail to the customer. Two agents, because
 * one account may add 30 comments a minute (COM-8) and the 50 mails are to go out at one moment of the
 * app's clock.
 *
 * <p>Each test makes its people first and then moves the clock 61 minutes on, so the invitation the
 * customer got at that address is not counted. A mail the limit stopped leaves a failed row; before and
 * after each test the clock moves eight days on and the test waits until no failed row is left.
 *
 * <p>The metric is taken to be {@link #STOPPED_TOTAL} ({@code design.md} 0절 6). If the implementation
 * picks another name, this test changes with it.
 */
class MailLimitTest extends Delivery.PlainApp {

    /** Mails the limit stopped, a counter without labels. */
    static final String STOPPED_TOTAL = "popdesk_mail_rate_limited_total";

    private static final Duration MANY_MAILS = Duration.ofSeconds(60);

    private Cast c;
    private Session secondAgent;
    private String key;

    @BeforeEach
    void peopleAndANewHour() {
        startClean();
        c = cast();
        secondAgent = person(c.admin(), Role.AGENT, "Staffer", c.code());
        key = fixtures.fileIssue(c.customer(), c.code()).key();
        clock.advance(Delivery.MAIL_LIMIT_WINDOW.plusMinutes(1));
    }

    @AfterEach
    void leaveNoFailedRow() {
        startClean();
    }

    private int mailsToCustomer() {
        return notifications(mail(), c.customer().email()).size();
    }

    private void awaitMailsToCustomer(int n) {
        Wait.untilAsserted(n + " mails to the customer", MANY_MAILS, () -> assertThat(mailsToCustomer()).isEqualTo(n));
    }

    /** The two agents answer {@code n} times in all, at this moment of the clock, and the mails arrive. */
    private void sendAtOnce(int n) {
        int before = mailsToCustomer();
        answer(c.agent(), key, n / 2);
        answer(secondAgent, key, n - n / 2);
        awaitMailsToCustomer(before + n);
    }

    private List<JsonNode> stopped() {
        return Delivery.failedMailRows(c.admin(), c.customer().accountId());
    }

    private JsonNode awaitStopped(int n) {
        return Wait.until(n + " failed mail row(s) of the customer", () -> {
            List<JsonNode> rows = stopped();
            return rows.size() == n ? rows.getFirst() : null;
        });
    }

    @Test
    @DisplayName("S4B-AC-10 after 50 mails to one address within the hour the 51st is not sent and its row is "
            + "failed with RATE_LIMITED, while a mail to another address goes out")
    void fiftyFirstMailIsStopped() {
        Session other = person(c.admin(), Role.CUSTOMER, "Other", c.code());
        String otherKey = fixtures.fileIssue(other, c.code()).key();
        sendAtOnce(MAIL_LIMIT);

        answer(c.agent(), key, 1);

        JsonNode row = awaitStopped(1);
        assertThat(row.path("lastErrorKind").asString(null)).as("lastErrorKind of %s", row).isEqualTo("RATE_LIMITED");
        answer(c.agent(), otherKey, 1);
        NotifySteps.awaitNotification(mail(), other.email());
        assertThat(mailsToCustomer()).as("mails to the customer once a later mail went out").isEqualTo(MAIL_LIMIT);
        assertThat(Delivery.failedMailRows(c.admin(), other.accountId())).as("failed mail rows of the other "
                + "customer").isEmpty();
    }

    @Test
    @DisplayName("S4B-AC-11 59 minutes after 50 mails went out at once the next is still stopped, 61 minutes "
            + "after it goes out, and the stopped row stays failed and is not sent")
    void limitEndsAfterAnHour() {
        sendAtOnce(MAIL_LIMIT);

        clock.advance(Duration.ofMinutes(59));
        answer(c.agent(), key, 1);
        JsonNode row = awaitStopped(1);
        assertThat(mailsToCustomer()).as("mails to the customer 59 minutes later").isEqualTo(MAIL_LIMIT);

        clock.advance(Duration.ofMinutes(2));
        answer(c.agent(), key, 1);
        awaitMailsToCustomer(MAIL_LIMIT + 1);

        awaitALaterMail(c.admin());
        assertThat(mailsToCustomer()).as("mails to the customer once a later mail went out")
                .isEqualTo(MAIL_LIMIT + 1);
        assertThat(Delivery.isFailed(c.admin(), row.path("id").asLong(-1))).as("the stopped row is still failed")
                .isTrue();
    }

    @Test
    @DisplayName("S4B-AC-12 a mail the limit stopped adds one to the metric and puts DELIVERY_FAILED in the "
            + "admin's inbox")
    void stoppedMailIsCountedAndAlerts() {
        long alertsBefore = alerts();
        double before = Math.max(metric(STOPPED_TOTAL), 0);
        sendAtOnce(MAIL_LIMIT);

        answer(c.agent(), key, 1);

        awaitStopped(1);
        Wait.untilAsserted(STOPPED_TOTAL, () -> assertThat(metric(STOPPED_TOTAL)).isEqualTo(before + 1));
        Wait.untilAsserted("one more DELIVERY_FAILED item in the admin's inbox",
                () -> assertThat(alerts()).isEqualTo(alertsBefore + 1));
    }

    private long alerts() {
        return TalkFlow.inbox(c.admin()).stream().filter(n -> "DELIVERY_FAILED".equals(TalkFlow.kind(n))).count();
    }

    @Test
    @DisplayName("S4B-AC-13 an attempt the SMTP server refused is not counted: after 49 mails and one refused "
            + "attempt the retry is the 50th and goes out, and the next mail is stopped")
    void refusedAttemptIsNotCounted() {
        sendAtOnce(MAIL_LIMIT - 1);
        smtp().refuseMessage("451 4.3.0 Refused by the test, try again later");
        answer(c.agent(), key, 1);
        smtp().awaitTakenTo(c.customer().email());
        smtp().pass();

        clock.advance(Duration.ofSeconds(1));
        awaitMailsToCustomer(MAIL_LIMIT);
        assertThat(stopped()).as("failed mail rows of the customer after the retry").isEmpty();

        answer(c.agent(), key, 1);
        JsonNode row = awaitStopped(1);
        assertThat(row.path("lastErrorKind").asString(null)).as("lastErrorKind of %s", row).isEqualTo("RATE_LIMITED");
    }
}
