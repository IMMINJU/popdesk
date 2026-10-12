package io.popdesk.notify;

import static io.popdesk.notify.NotifySteps.awaitWebhooks;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.IssueFlow.OnBehalf;
import io.popdesk.notify.NotifySteps.Scene;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import io.popdesk.support.WebhookReceiver.Received;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Team webhook messages of slice 3 (HOOK-1): an issue is resolved, closed, or reopened, a person sets
 * or clears its assignee, and staff file an issue for a customer. The exact wording is fixed by
 * {@link IssueWebhookSnapshots}.
 *
 * <p>"No webhook" is counted after a later change of the same issue posted its own: the count of
 * requests that hold the issue key goes up by exactly one per change that posts.
 */
class IssueWebhookTest extends ConnectedAppTest {

    private NotifySteps steps;
    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        steps = new NotifySteps(fixtures);
        flow = new IssueFlow(fixtures, clock);
    }

    /** Waits for the {@code n}th webhook of the issue and fails when more than {@code n} came. */
    private List<Received> exactly(String key, int n, String after) {
        List<Received> found = awaitWebhooks(webhook(), key, n);
        assertThat(found).as("webhooks of %s after %s", key, after).hasSize(n);
        return found;
    }

    @Test
    @DisplayName("S3-AC-73 resolving, closing and reopening by staff and by the customer, and setting and clearing the assignee each post one webhook; confirming, starting, a refused change, and the same assignee again post none")
    void oneWebhookPerChangeThatPosts() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        String key = issue.key();
        exactly(key, 1, "filing");

        flow.changeStatus(s.agent(), key, "CONFIRMED");
        flow.changeStatus(s.agent(), key, "IN_PROGRESS");
        ApiResponse refused = IssueFlow.statusRequest(s.agent(), key, "CLOSED").send();
        assertThat(refused.status()).as("IN_PROGRESS to CLOSED: " + refused.snippet()).isEqualTo(400);
        flow.changeStatus(s.agent(), key, "RESOLVED");
        exactly(key, 2, "confirming, starting, a refused closing, and resolving");

        flow.reopen(s.agent(), key);
        exactly(key, 3, "reopening by staff");

        flow.moveTo(s.agent(), key, "RESOLVED");
        exactly(key, 4, "starting and resolving again");

        flow.reopen(s.customer(), key);
        exactly(key, 5, "reopening by the customer");

        flow.changeStatus(s.admin(), key, "CLOSED");
        exactly(key, 6, "closing by staff");

        flow.reopen(s.admin(), key);
        exactly(key, 7, "reopening the closed issue");

        flow.close(s.customer(), key);
        exactly(key, 8, "closing by the customer");

        flow.assign(s.admin(), key, s.agent().accountId());
        exactly(key, 9, "setting the assignee");

        flow.assign(s.agent(), key, s.agent().accountId());
        flow.unassign(s.agent(), key);
        exactly(key, 10, "the same assignee again and clearing the assignee");

        flow.unassign(s.agent(), key);
        Wait.holds("ten webhooks for " + key, Duration.ofSeconds(3), () -> webhook().containing(key).size() == 10);
    }

    @Test
    @DisplayName("S3-AC-74 each message has the issue key, the title, the company code, and the console link, and no name or email of the reporter, the assignee, or the person who made the change, and no word of the content")
    void messagesHoldNoPersonAndNoContent() {
        Scene s = steps.scene();
        // The default company name holds the code; name it so that the code is found on its own.
        long n = Unique.number();
        Company company = fixtures.createCompany(s.admin(), Unique.companyCode(), "Hooked company " + n,
                "Hooked company " + n + " Ltd.");
        Session customer = steps.person(s.admin(), Role.CUSTOMER, "Customer", company.code());
        Session boss = steps.person(s.admin(), Role.ADMIN, "Boss", null);
        fixtures.assignAgent(s.admin(), company.code(), s.agent().accountId());
        String title = Unique.text("Hooked title");
        String content = "Hookedcontent" + Unique.number() + " secretword" + Unique.number();
        Issue issue = fixtures.fileIssue(customer, NewIssue.in(company.code()).title(title).content(content));
        String key = issue.key();

        flow.assign(boss, key, s.agent().accountId());
        flow.moveTo(s.agent(), key, "RESOLVED");
        flow.reopen(customer, key);
        flow.changeStatus(s.agent(), key, "CLOSED");
        flow.unassign(boss, key);

        List<Received> sent = exactly(key, 6, "an assignee, resolving, reopening, closing, and clearing the assignee");
        assertThat(sent.subList(1, 6)).allSatisfy(request -> {
            assertThat(request.json().propertyNames()).as("fields of %s", request.text()).containsExactly("text");
            String text = request.json().path("text").asString("");
            assertThat(text).contains(key).contains(title).contains(NotifySteps.consoleIssueLink(key))
                    .doesNotContain(PORTAL_HOST);
            assertThat(text.replace(NotifySteps.consoleIssueLink(key), "").replace(key, ""))
                    .as("the text without the key and the link").contains(company.code());
            for (Session person : List.of(customer, s.agent(), boss)) {
                assertThat(text).doesNotContain(person.name()).doesNotContainIgnoringCase(person.email());
            }
            for (String word : content.split(" ")) {
                assertThat(text).doesNotContain(word);
            }
        });
        assertThat(sent.subList(1, 6).stream().map(Received::text).distinct().count())
                .as("kinds of message among: an assignee change, resolved, reopened, closed, an assignee change")
                .isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("S3-AC-75 an issue staff file for a customer posts the same message as one filed on the portal")
    void filingOnBehalfPostsTheNewIssueMessage() {
        Scene s = steps.scene();
        Issue onPortal = fixtures.fileIssue(s.customer(), NewIssue.in(s.code()).title(WebhookSnapshots.TITLE));
        String ofPortal = exactly(onPortal.key(), 1, "filing on the portal").getFirst().text();

        Issue onBehalf = flow.fileOnBehalf(s.agent(), OnBehalf.of(s.code(), s.customer().accountId())
                .title(WebhookSnapshots.TITLE));

        String ofBehalf = exactly(onBehalf.key(), 1, "filing on behalf").getFirst().text();
        assertThat(ofBehalf.replace(onBehalf.key(), "{key}")).as("message of the issue filed on behalf")
                .isEqualTo(ofPortal.replace(onPortal.key(), "{key}"));
        assertThat(ofBehalf).doesNotContain(s.agent().name()).doesNotContainIgnoringCase(s.agent().email());
        Wait.holds("one webhook for the issue filed on behalf", Duration.ofSeconds(3),
                () -> webhook().containing(onBehalf.key()).size() == 1);
    }

    @Test
    @DisplayName("S3-AC-70 taking an agent off a company and suspending an account with clearAssignments=true post no webhook for the cleared issues")
    void clearingAssignmentsPostsNothing() {
        Scene s = steps.scene();
        Issue first = fixtures.fileIssue(s.customer(), s.code());
        Issue second = fixtures.fileIssue(s.customer(), s.code());
        flow.assign(s.admin(), first.key(), s.agent().accountId());
        flow.assign(s.admin(), second.key(), s.agent().accountId());
        exactly(first.key(), 2, "filing and an assignee");
        exactly(second.key(), 2, "filing and an assignee");

        ApiResponse unassigned = s.admin().client()
                .delete("/api/console/companies/" + s.code() + "/agents/" + s.agent().accountId())
                .query("clearAssignments", true).expect(200);
        assertThat(unassigned.jsonLong("issues")).as("issues cleared by taking the agent off").isEqualTo(2);
        flow.assign(s.admin(), first.key(), s.admin().accountId());
        exactly(first.key(), 3, "clearing with the agent's assignment, then an assignee set by a person");
        Session other = steps.person(s.admin(), Role.AGENT, "Staffer", null);
        fixtures.assignAgent(s.admin(), s.code(), other.accountId());
        flow.assign(s.admin(), second.key(), other.accountId());
        exactly(second.key(), 3, "clearing with the agent's assignment, then an assignee set by a person");

        ApiResponse suspended = s.admin().client().post("/api/console/accounts/" + other.accountId() + "/suspend")
                .query("clearAssignments", true).expect(200);
        assertThat(suspended.json().path("cleared").path("issues").asLong(-1)).as("issues cleared by suspending")
                .isEqualTo(1);
        flow.changeStatus(s.admin(), second.key(), "CLOSED");
        exactly(second.key(), 4, "clearing with the suspension, then closing");
        Wait.holds("no webhook for the cleared assignees", Duration.ofSeconds(3),
                () -> webhook().containing(first.key()).size() == 3 && webhook().containing(second.key()).size() == 4);
    }
}
