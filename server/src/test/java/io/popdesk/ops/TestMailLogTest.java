package io.popdesk.ops;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.notify.Delivery;
import io.popdesk.support.AppLog.Mark;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailServer;
import io.popdesk.support.Unique;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * The log after test mails (PII-2, TM-30): the address a test mail went to is there masked after its
 * first character or not at all, and what the SMTP server answered is not there. The server here
 * repeats the address and the SMTP password in its reply, so a reply written to the log as it came
 * would show both.
 */
class TestMailLogTest extends Delivery.PlainApp {

    @Test
    @DisplayName("S4B-AC-38 test mails that went out and that were refused leave no form of the address, no word "
            + "of the server's reply, and no SMTP password in the log")
    void testMailsLeaveNoAddressOrReplyInTheLog() {
        Session tester = person(admin(), Role.ADMIN, "Tester");
        String word = IssueFlow.word();
        Mark mark = log.mark();

        JsonNode sent = Delivery.testMail(tester);
        smtp().script(line -> line.toUpperCase(Locale.ROOT).startsWith("RCPT TO:")
                ? "550 5.1.1 " + line.substring(8).strip() + " unknown " + word + " " + MailServer.PASSWORD : null);
        JsonNode refused = Delivery.testMail(tester);
        smtp().pass();

        assertThat(sent.path("ok").asBoolean(false)).as("ok of the first test mail").isTrue();
        assertThat(refused.path("serverReply").asString("")).as("serverReply of the refused test mail")
                .contains(word);
        String probe = "test-mail-log-probe-" + Unique.number();
        LoggerFactory.getLogger("io.popdesk.ops.LogProbe").warn(probe);
        log.awaitLine(mark, probe);
        String since = log.since(mark);
        assertThat(since.lines().filter(line -> !line.isBlank()).count()).as("log lines since the mark").isPositive();
        assertThat(Delivery.foundIn(since, tester.email())).as("forms of the address in the log").isEmpty();
        assertThat(LogForms.foundIn(since, List.of(tester.email(), word, MailServer.PASSWORD)))
                .as("the address, the reply, and the SMTP password in the log").isEmpty();
    }
}
