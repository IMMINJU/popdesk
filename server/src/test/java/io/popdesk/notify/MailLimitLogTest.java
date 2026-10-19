package io.popdesk.notify;

import static io.popdesk.notify.Delivery.MAIL_LIMIT;
import static io.popdesk.notify.NotifySteps.notifications;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.AppLog;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The log of a mail the limit of one address stopped (B2-AC-07): one INFO line per stopped mail, and a single WARN
 * for the alert {@code DELIVERY_FAILED}. A mail the SMTP server refused stays a WARN.
 *
 * <p>The mails are made as in {@link MailLimitTest}: two agents answer one issue of the customer at one moment of the
 * app's clock. The log is the JSON log of the app ({@code level}, {@code message}), read after a mark.
 */
class MailLimitLogTest extends Delivery.PlainApp {

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

    private void awaitMailsToCustomer(int n) {
        Wait.untilAsserted(n + " mails to the customer", MANY_MAILS,
                () -> assertThat(notifications(mail(), c.customer().email())).hasSize(n));
    }

    private static List<JsonNode> at(List<JsonNode> lines, String level) {
        return lines.stream().filter(l -> level.equals(l.path("level").asString(null))).toList();
    }

    /** The lines whose message holds the text, ignoring case: the channel may be written {@code mail} or {@code MAIL}. */
    private static List<JsonNode> holding(List<JsonNode> lines, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lines.stream().filter(l -> l.path("message").asString("").toLowerCase(Locale.ROOT).contains(lower))
                .toList();
    }

    @Test
    @DisplayName("B2-AC-07 the 51st mail to one address within the hour leaves the INFO line \"Outbox row {id} (mail) "
            + "was not sent: RATE_LIMITED. It is left as failed.\" and no WARN about its row; the one WARN is the "
            + "line of the DELIVERY_FAILED alert")
    void stoppedMailIsInfo() {
        int before = notifications(mail(), c.customer().email()).size();
        answer(c.agent(), key, MAIL_LIMIT / 2);
        answer(secondAgent, key, MAIL_LIMIT - MAIL_LIMIT / 2);
        awaitMailsToCustomer(before + MAIL_LIMIT);
        AppLog.Mark mark = log.mark();

        answer(c.agent(), key, 1);

        JsonNode row = Wait.until("a failed mail row of the customer", () -> {
            List<JsonNode> rows = Delivery.failedMailRows(c.admin(), c.customer().accountId());
            return rows.size() == 1 ? rows.getFirst() : null;
        });
        long id = row.path("id").asLong(-1);
        String expected = "Outbox row " + id + " (mail) was not sent: RATE_LIMITED. It is left as failed.";
        Wait.until("a log line holding " + expected,
                () -> holding(log.jsonLines(mark), expected).isEmpty() ? null : true);
        Wait.until("a log line about DELIVERY_FAILED",
                () -> holding(log.jsonLines(mark), "DELIVERY_FAILED").isEmpty() ? null : true);
        List<JsonNode> lines = log.jsonLines(mark);
        assertThat(holding(lines, expected)).as("lines of the stopped row %d", id).isNotEmpty()
                .allSatisfy(l -> assertThat(l.path("level").asString(null)).as("level of %s", l).isEqualTo("INFO"));
        assertThat(holding(at(lines, "WARN"), "Outbox row " + id + " ")).as("WARN lines about row %d", id).isEmpty();
        assertThat(holding(at(lines, "WARN"), "DELIVERY_FAILED")).as("WARN lines about the alert DELIVERY_FAILED")
                .hasSize(1);
    }

    @Test
    @DisplayName("B2-AC-07 a mail the SMTP server refused still leaves a WARN line about its row, whatever the mail "
            + "limit lines say")
    void refusedMailIsStillWarn() {
        AppLog.Mark mark = log.mark();
        smtp().refuseMessage("451 4.3.0 Refused by the test, try again later");

        answer(c.agent(), key, 1);

        smtp().awaitTakenTo(c.customer().email());
        smtp().pass();
        JsonNode warn = Wait.until("a WARN line about a mail row that failed", () -> {
            List<JsonNode> found = holding(at(log.jsonLines(mark), "WARN"), "(mail) failed");
            return found.isEmpty() ? null : found.getFirst();
        });
        assertThat(warn.path("message").asString("")).as("message of %s", warn).startsWith("Sending outbox row ");
    }
}
