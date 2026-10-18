package io.popdesk.privacy;

import static io.popdesk.privacy.PrivacySteps.anonymize;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.TalkFlow;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import io.popdesk.task.TaskSteps;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * After anonymizing, what the person wrote stays and shows the anonymous name as its author (S10A-AC-11): on the
 * console the issue's reporter, comments, the task and its note, the history and the inbox of someone else; on the
 * portal the customer's own writing, while staff writing stays "the support team" without a name.
 */
class AnonymousAuthorTest extends PrivacyAppTest {

    @Test
    @DisplayName("S10A-AC-11 after a customer and an agent are anonymized, the console shows the anonymous name as "
            + "reporter, comment authors, task creator, note author, history actor and inbox actor, the portal shows "
            + "it for the customer and still the nameless support team for the agent, and every content is kept")
    void anonymousNameAsAuthor() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        Session colleague = fixtures.customer(admin, company.code());
        Session agent = fixtures.agent(admin, company.code());
        Session assignee = fixtures.agent(admin, company.code());
        Issue issue = fixtures.fileIssue(customer, company.code());
        flow.assign(admin, issue.key(), assignee.accountId());
        String customerText = Unique.text("Customer words");
        Comment ofCustomer = fixtures.comment(customer, issue.key(), customerText);
        Comment ofAgent = TalkFlow.mention(agent, issue.key(), false, List.of(assignee.accountId()));
        flow.changeStatus(agent, issue.key(), "CONFIRMED");
        JsonNode task = TaskSteps.create(agent, issue.key());
        String noteText = Unique.text("Note words");
        TaskSteps.note(agent, issue.key(), TaskSteps.id(task), noteText);
        Wait.until("inbox items of the assignee by the customer and by the agent", () -> {
            List<Long> actors = TalkFlow.inboxOf(assignee, issue.key()).stream()
                    .map(n -> n.path("actor").path("accountId").asLong(-1)).toList();
            return actors.contains(customer.accountId()) && actors.contains(agent.accountId()) ? actors : null;
        });

        anonymize(admin, customer.accountId());
        anonymize(admin, agent.accountId());

        JsonNode detail = flow.detail(admin, issue.key());
        assertThat(detail.path("reporter").path("name").asString(null)).as("reporter on the console")
                .isEqualTo(ANONYMOUS_NAME);
        assertThat(detail.path("content").asString(null)).as("content of the issue")
                .isEqualTo(issue.json().path("content").asString(null));
        JsonNode customerComment = TalkFlow.comment(admin, issue.key(), ofCustomer.id());
        assertThat(customerComment.path("author").path("name").asString(null)).as("author of %s", customerComment)
                .isEqualTo(ANONYMOUS_NAME);
        assertThat(customerComment.path("content").asString(null)).as("content of %s", customerComment)
                .isEqualTo(customerText);
        JsonNode agentComment = TalkFlow.comment(admin, issue.key(), ofAgent.id());
        assertThat(agentComment.path("author").path("name").asString(null)).as("author of %s", agentComment)
                .isEqualTo(ANONYMOUS_NAME);
        JsonNode taskNow = TaskSteps.task(admin, issue.key(), TaskSteps.id(task));
        assertThat(taskNow.path("createdBy").path("name").asString(null)).as("createdBy of %s", taskNow)
                .isEqualTo(ANONYMOUS_NAME);
        List<JsonNode> notes = taskNow.path("notes").values().stream()
                .filter(n -> noteText.equals(n.path("content").asString(null))).toList();
        assertThat(notes).as("the note in %s", taskNow).hasSize(1);
        assertThat(notes.getFirst().path("author").path("name").asString(null)).as("author of the note")
                .isEqualTo(ANONYMOUS_NAME);
        List<JsonNode> byAgent = flow.history(admin, issue.key()).stream()
                .filter(h -> h.path("actor").path("accountId").asLong(-1) == agent.accountId()).toList();
        assertThat(byAgent).as("history entries by the agent").isNotEmpty()
                .allSatisfy(h -> assertThat(h.path("actor").path("name").asString(null)).as("actor of %s", h)
                        .isEqualTo(ANONYMOUS_NAME));
        List<JsonNode> inbox = TalkFlow.inboxOf(assignee, issue.key()).stream()
                .filter(n -> List.of(customer.accountId(), agent.accountId())
                        .contains(n.path("actor").path("accountId").asLong(-1))).toList();
        assertThat(inbox).as("inbox items of the assignee by the two").isNotEmpty()
                .allSatisfy(n -> assertThat(n.path("actor").path("name").asString(null)).as("actor of %s", n)
                        .isEqualTo(ANONYMOUS_NAME));

        JsonNode onPortal = flow.detail(colleague, issue.key());
        assertThat(onPortal.path("reporter").path("name").asString(null)).as("reporter on the portal")
                .isEqualTo(ANONYMOUS_NAME);
        JsonNode customerOnPortal = TalkFlow.comment(colleague, issue.key(), ofCustomer.id());
        assertThat(customerOnPortal.path("author").path("name").asString(null)).as("author of %s", customerOnPortal)
                .isEqualTo(ANONYMOUS_NAME);
        JsonNode agentOnPortal = TalkFlow.comment(colleague, issue.key(), ofAgent.id()).path("author");
        assertThat(agentOnPortal.path("kind").asString(null)).as("kind of %s", agentOnPortal)
                .isEqualTo("SUPPORT_TEAM");
        assertThat(agentOnPortal.path("name").isNull() && agentOnPortal.path("accountId").isNull())
                .as("no name and no account ID in %s", agentOnPortal).isTrue();
    }
}
