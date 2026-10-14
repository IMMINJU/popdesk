package io.popdesk.label;

import static io.popdesk.label.LabelSteps.id;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Labels and due dates are told to nobody ({@code acceptance.md} S6A-AC-63): no inbox item, no mail, and no
 * webhook request for the reporter or the assignee. What came is read after a later event came: the reporter
 * comments (an inbox item for the assignee and a webhook request), an invitation is mailed (it leaves the mail
 * queue after everything queued before it), and another issue is filed (its webhook comes after those queued
 * before). The comment is done once before the changes and once after, so what one comment brings is known.
 */
class LabelQuietTest extends ConnectedAppTest {

    /** What the reporter, the assignee, and the webhook receiver got about one issue. */
    private record Told(List<Long> reporterInbox, List<Long> assigneeInbox, int mailToReporter, int mailToAssignee,
            int webhooks) {}

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    /** The reporter comments; waits for that and for later mail and webhook; reads what came about {@code key}. */
    private Told told(Session admin, Session reporter, Session assignee, String code, String key) {
        Comment comment = fixtures.comment(reporter, key, Unique.text("Later question"));
        Wait.until("the assignee's inbox item of comment " + comment.id(), () -> TalkFlow.inboxOf(assignee, key)
                .stream().anyMatch(n -> n.path("commentId").asLong(-1) == comment.id()) ? Boolean.TRUE : null);
        TalkFlow.awaitALaterMail(fixtures, admin);
        Issue later = fixtures.fileIssue(reporter, code);
        Wait.until("the webhook of the issue filed afterwards, " + later.key(),
                () -> webhook().containing(later.key()).isEmpty() ? null : Boolean.TRUE);
        return new Told(TalkFlow.ids(TalkFlow.inboxOf(reporter, key)), TalkFlow.ids(TalkFlow.inboxOf(assignee, key)),
                mail().messagesTo(reporter.email()).size(), mail().messagesTo(assignee.email()).size(),
                webhook().containing(key).size());
    }

    @Test
    @DisplayName("S6A-AC-63 adding and removing labels, deleting a label on the issue, and setting, changing, and clearing the due date bring the reporter and the assignee no inbox item and no mail, and post no webhook")
    void labelsAndDueDatesAreToldToNobody() {
        Session admin = flow.admin();
        Company company = fixtures.createCompany(admin);
        Session reporter = fixtures.customer(admin, company.code());
        Session assignee = fixtures.agent(admin, company.code());
        String key = fixtures.fileIssue(reporter, company.code()).key();
        flow.assign(admin, key, assignee.accountId());
        JsonNode removed = LabelSteps.create(admin, company.code());
        JsonNode deleted = LabelSteps.create(admin, company.code());
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        Told first = told(admin, reporter, assignee, company.code(), key);
        Told second = told(admin, reporter, assignee, company.code(), key);

        LabelSteps.add(admin, key, id(removed));
        LabelSteps.add(admin, key, id(deleted));
        LabelSteps.setDueDate(admin, key, today.plusDays(3));
        LabelSteps.setDueDate(admin, key, today.plusDays(4));
        LabelSteps.clearDueDateRequest(admin, key).expect(200);
        LabelSteps.setDueDate(admin, key, today.plusDays(5));
        LabelSteps.remove(admin, key, id(removed));
        LabelSteps.delete(admin, company.code(), id(deleted));
        Told third = told(admin, reporter, assignee, company.code(), key);

        assertThat(second.assigneeInbox().size() - first.assigneeInbox().size())
                .as("the assignee's items that one comment brings").isEqualTo(1);
        assertThat(third.reporterInbox().size() - second.reporterInbox().size())
                .as("the reporter's new items, after the changes and a comment")
                .isEqualTo(second.reporterInbox().size() - first.reporterInbox().size());
        assertThat(third.assigneeInbox().size() - second.assigneeInbox().size())
                .as("the assignee's new items, after the changes and a comment").isEqualTo(1);
        assertThat(third.mailToReporter() - second.mailToReporter())
                .as("mails to the reporter, after the changes and a comment")
                .isEqualTo(second.mailToReporter() - first.mailToReporter());
        assertThat(third.mailToAssignee() - second.mailToAssignee())
                .as("mails to the assignee, after the changes and a comment")
                .isEqualTo(second.mailToAssignee() - first.mailToAssignee());
        assertThat(third.webhooks() - second.webhooks()).as("webhook requests, after the changes and a comment")
                .isEqualTo(second.webhooks() - first.webhooks());
        assertThat(second.webhooks() - first.webhooks()).as("webhook requests one comment brings").isPositive();
    }
}
