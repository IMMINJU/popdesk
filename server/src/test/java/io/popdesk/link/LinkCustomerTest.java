package io.popdesk.link;

import static io.popdesk.link.LinkSteps.DUPLICATE_OF;
import static io.popdesk.link.LinkSteps.RELATED;
import static io.popdesk.link.LinkSteps.described;
import static io.popdesk.link.LinkSteps.titleOf;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.link.LinkSteps.World;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.ReceivedMail;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Customers and links (LINK-3, TM-41): a customer reaches no link path, and nothing a customer reads or is mailed
 * shows a link or the other issue. With SMTP (setup "connected"), so that the customers' mails arrive.
 */
class LinkCustomerTest extends ConnectedAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    // S6C-AC-40

    @Test
    @DisplayName("S6C-AC-40 a customer calling the three link paths gets what a console path already gives a customer, on the portal host and with the session carried to the console host, and the links stay")
    void customerGetsTheConsoleAnswer() {
        World w = LinkSteps.world(fixtures, flow);
        Issue mine = w.issueOfA(fixtures);
        Issue other = w.issueOfA(fixtures);
        Issue third = w.issueOfA(fixtures);
        LinkSteps.link(w.linker(), mine.key(), other.key(), RELATED);
        Session customer = w.reporterA();
        ApiClient carried = console();
        customer.client().cookies().forEach(carried::setCookie);

        for (ApiClient client : List.of(customer.client(), carried)) {
            ApiResponse existing = client.get("/api/console/issues/" + mine.key()).send();
            List<ApiResponse> answers = List.of(
                    client.get(LinkSteps.linksPath(mine.key())).send(),
                    client.post(LinkSteps.linksPath(mine.key()))
                            .json(LinkSteps.body("issueKey", third.key(), "type", RELATED)).send(),
                    client.delete(LinkSteps.linkPath(mine.key(), other.key())).send());

            assertThat(existing.status()).as(existing.request() + " -> " + existing.snippet()).isIn(401, 404);
            for (ApiResponse answer : answers) {
                assertThat(answer.status()).as(answer.request() + " -> " + answer.snippet())
                        .isEqualTo(existing.status());
                assertThat(answer.errorCode()).as("code of " + answer.request()).isEqualTo(existing.errorCode());
            }
        }
        assertThat(described(w.admin(), mine.key())).as("links after the customer's requests")
                .containsExactly(RELATED + " " + other.key());
    }

    // S6C-AC-41

    /** What a customer reads about their issue on the portal: the detail and its item in the issue list. */
    private static String portalView(Session customer, String key) {
        JsonNode detail = customer.client().get("/api/portal/issues/" + key).expect(200).json();
        JsonNode item = TalkFlow.items(IssueFlow.list(customer)).stream()
                .filter(n -> key.equals(n.path("key").asString(null))).findFirst()
                .orElseThrow(() -> new AssertionError(key + " is not in the issue list of " + customer.email()));
        return detail + "\n" + item;
    }

    @Test
    @DisplayName("S6C-AC-41 when a customer's issue is linked to another company's issue, the portal detail and list are as before, and the portal inbox, list, and mails of both customers, through both issues being resolved, never hold a link or the other issue's key and title")
    void customersSeeNoLink() {
        World w = LinkSteps.world(fixtures, flow);
        Issue ofA = w.issueOfA(fixtures);
        Issue ofB = w.issueOfB(fixtures);
        Session assignee = fixtures.agent(w.admin(), w.a().code(), w.b().code());
        flow.assign(w.admin(), ofA.key(), assignee.accountId());
        flow.assign(w.admin(), ofB.key(), assignee.accountId());
        String viewA = portalView(w.reporterA(), ofA.key());
        String viewB = portalView(w.reporterB(), ofB.key());

        LinkSteps.link(w.linker(), ofA.key(), ofB.key(), DUPLICATE_OF);

        assertThat(portalView(w.reporterA(), ofA.key())).as("A's customer's view after linking").isEqualTo(viewA);
        assertThat(portalView(w.reporterB(), ofB.key())).as("B's customer's view after linking").isEqualTo(viewB);
        flow.moveTo(w.admin(), ofB.key(), "RESOLVED");
        flow.moveTo(w.admin(), ofA.key(), "RESOLVED");
        flow.close(w.reporterB(), ofB.key());
        TalkFlow.awaitALaterMail(fixtures, w.admin());

        Map<Session, Issue> otherOf = Map.of(w.reporterA(), ofB, w.reporterB(), ofA);
        Map<Session, Issue> ownOf = Map.of(w.reporterA(), ofA, w.reporterB(), ofB);
        for (Session customer : List.of(w.reporterA(), w.reporterB())) {
            Issue other = otherOf.get(customer);
            Issue own = ownOf.get(customer);
            List<ReceivedMail> mails = mail().messagesTo(customer.email());
            List<String> read = List.of(
                    customer.client().get("/api/portal/issues/" + own.key()).expect(200).text(),
                    IssueFlow.list(customer).text(),
                    customer.client().get("/api/portal/notifications").query("size", 100).expect(200).text());

            assertThat(TalkFlow.inboxOf(customer, own.key())).as("portal items of %s", customer.email()).isNotEmpty();
            assertThat(mails).as("mails to %s", customer.email()).isNotEmpty();
            for (String text : read) {
                assertThat(text).as("what %s reads", customer.email()).doesNotContain(other.key())
                        .doesNotContain(titleOf(other)).doesNotContain("linkedIssue").doesNotContain(DUPLICATE_OF)
                        .doesNotContain("DUPLICATED_BY");
            }
            for (ReceivedMail m : mails) {
                assertThat(m.subject() + "\n" + m.text()).as("mail to %s", customer.email())
                        .doesNotContain(other.key()).doesNotContain(titleOf(other));
            }
        }
        assertThat(TalkFlow.kinds(TalkFlow.inboxOf(assignee, ofA.key()))).as("items of the staff assignee")
                .contains(LinkSteps.LINKED_RESOLVED);
    }
}
