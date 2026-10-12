package io.popdesk.notify;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.support.AppLog;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Without SMTP a new mail of slice 4 is not sent and leaves one log line with the address masked, as
 * the mails before it do ({@link NoSmtpMailLogTest}); the inbox item is made all the same. The customer
 * here gets an address that starts with {@code q}, as in that test, so a masked {@code q***} in the log
 * is theirs.
 */
class NoSmtpTalkMailLogTest extends BareAppTest {

    private static final Pattern MASKED_Q = Pattern.compile("(?<![A-Za-z0-9])q[*•…]+");

    /** Lines since {@code mark} that hold a masked address starting with q. */
    private List<String> maskedLines(AppLog.Mark mark) {
        return log.lines(mark).stream().filter(l -> MASKED_Q.matcher(l).find()).toList();
    }

    private void awaitMaskedLines(AppLog.Mark mark, int n) {
        Wait.until(n + " log line(s) with the masked address q***", () -> maskedLines(mark).size() >= n ? mark : null);
    }

    @Test
    @DisplayName("S4-AC-57 without SMTP an agent resolving an issue writes one log line for the reporter's mail, "
            + "the address masked, and still puts the item in the reporter's inbox")
    void resolvedMailIsALogLineAndTheItemIsThere() {
        IssueFlow flow = new IssueFlow(fixtures, clock);
        Session admin = flow.admin();
        Company company = fixtures.createCompany(admin);
        AppLog.Mark invited = log.mark();
        Invitation invitation = fixtures.invite(admin, Role.CUSTOMER, Unique.email("q"), Unique.text("Quinn"),
                List.of(company.code()));
        awaitMaskedLines(invited, 1);
        Session customer = fixtures.accept(invitation);
        Session agent = fixtures.agent(admin, company.code());
        String key = fixtures.fileIssue(customer, company.code()).key();
        flow.changeStatus(agent, key, "IN_PROGRESS");
        AppLog.Mark mark = log.mark();

        flow.changeStatus(agent, key, "RESOLVED");
        awaitMaskedLines(mark, 1);
        // The line of a later mail to the same address: by then a second line for the first would be there.
        fixtures.comment(agent, key, "Answer " + Unique.number());
        awaitMaskedLines(mark, 2);

        assertThat(maskedLines(mark)).as("log lines for the two mails, one each").hasSize(2);
        assertThat(log.since(mark)).doesNotContainIgnoringCase(customer.email())
                .doesNotContain(customer.email().replace("@", "%40"));
        List<JsonNode> items = TalkFlow.inboxOf(customer, key);
        assertThat(items.stream().map(n -> n.path("kind").asString(null)).toList()).as("kinds of the reporter's items")
                .containsExactlyInAnyOrder("ISSUE_RESOLVED", "STAFF_COMMENT");
    }
}
