package io.popdesk.boundary;

import static io.popdesk.issue.IssueFlow.NO_KEY;
import static io.popdesk.issue.TalkFlow.assertRefused;
import static io.popdesk.issue.TalkFlow.body;
import static io.popdesk.issue.TalkFlow.ids;
import static io.popdesk.issue.TalkFlow.kinds;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The customer-company boundary on what slice 4 adds (S4-AC-70), where the tests of the comment and
 * inbox packages leave a side way open: the comments an agent wrote on an issue of a company they
 * were taken off, and the inboxes of the people of company A while things happen on an issue of
 * company B.
 *
 * <p>A customer of A and an agent of A editing or deleting a comment on B's issue is
 * {@code issue.CommentNotFoundTest}; the mention candidates of an issue the agent cannot see is
 * {@code issue.MentionCandidatesTest}; an item of a colleague or of another agent of the same company,
 * and reading an item that is someone else's, whatever their company, is {@code inbox.InboxReadTest}.
 * {@link BoundaryList} names them next to the tests here.
 */
class TalkBoundaryTest extends BareAppTest {

    /**
     * Two companies, each with a customer, an agent assigned to it alone, and a public issue its agent
     * is the assignee of and answered once. So each customer has one item (the answer) and each agent
     * one (the assignment), all unread.
     */
    private record Two(Session admin, Company a, Company b, Session customerOfA, Session agentOfA,
            Session customerOfB, Session agentOfB, Issue ofA, Issue ofB) {}

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    private Two two() {
        Session admin = flow.admin();
        Company a = fixtures.createCompany(admin);
        Company b = fixtures.createCompany(admin);
        Session customerOfA = fixtures.customer(admin, a.code());
        Session agentOfA = fixtures.agent(admin, a.code());
        Session customerOfB = fixtures.customer(admin, b.code());
        Session agentOfB = fixtures.agent(admin, b.code());
        Issue ofA = answered(admin, customerOfA, agentOfA, a.code());
        Issue ofB = answered(admin, customerOfB, agentOfB, b.code());
        return new Two(admin, a, b, customerOfA, agentOfA, customerOfB, agentOfB, ofA, ofB);
    }

    /** A public issue with a title of its own that the admin gave to the agent, who answered it. */
    private Issue answered(Session admin, Session customer, Session agent, String code) {
        Issue issue = fixtures.fileIssue(customer, NewIssue.in(code).title(Unique.text("Invoice " + IssueFlow.word())));
        flow.assign(admin, issue.key(), agent.accountId());
        fixtures.comment(agent, issue.key(), Unique.text("Answer"));
        return issue;
    }

    private static ApiResponse inboxPage(Session session) {
        return session.client().get(session.api("/notifications")).query("size", 100).expect(200);
    }

    private static List<String> issueKeys(List<JsonNode> items) {
        return items.stream().map(n -> n.path("issue").path("key").asString(null)).toList();
    }

    /** The newest item of the caller about the issue. Fails when there is none. */
    private static JsonNode newestItem(Session session, String key) {
        List<JsonNode> items = TalkFlow.inboxOf(session, key);
        assertThat(items).as("items of %s (%s) about %s", session.email(), session.role(), key).isNotEmpty();
        return items.getFirst();
    }

    private static void assertNotFound(ApiResponse answer, ApiResponse missing) {
        assertRefused(missing, 404, "NOT_FOUND");
        assertRefused(answer, 404, "NOT_FOUND");
        assertThat(answer.text()).as("body of %s, like for a target nobody has", answer.request())
                .isEqualTo(missing.text());
    }

    // Comments

    @Test
    @DisplayName("S4-AC-70, S4-AC-03 an agent taken off company B, still assigned to A, gets the 404 of an issue "
            + "key nobody has for editing and deleting the comments they wrote on B's issue, and the comments "
            + "stay as they were")
    void authorTakenOffTheCompanyFindsNoCommentOfTheirs() {
        Session admin = flow.admin();
        Company a = fixtures.createCompany(admin);
        Company b = fixtures.createCompany(admin);
        Session agent = fixtures.agent(admin, a.code(), b.code());
        Session customerOfB = fixtures.customer(admin, b.code());
        String key = fixtures.fileIssue(customerOfB, b.code()).key();
        Comment answer = fixtures.comment(agent, key, Unique.text("Answer while assigned"));
        Comment note = fixtures.internalComment(agent, key, Unique.text("Note while assigned"));
        // Both work while the agent is assigned, so a 404 below is about the assignment.
        TalkFlow.editContent(agent, key, answer.id(), Unique.text("Answer, edited while assigned"));
        TalkFlow.delete(agent, key, fixtures.comment(agent, key, Unique.text("Deleted while assigned")).id());
        flow.unassignAgent(admin, b.code(), agent.accountId());
        List<JsonNode> before = TalkFlow.comments(admin, key);
        flow.tick();

        for (Comment own : List.of(answer, note)) {
            ApiResponse edit = TalkFlow.editRequest(agent, key, own.id(), body("content", Unique.text("After")))
                    .send();
            ApiResponse editMissing = TalkFlow.editRequest(agent, NO_KEY, own.id(),
                    body("content", Unique.text("After"))).send();
            ApiResponse delete = TalkFlow.deleteRequest(agent, key, own.id()).send();
            ApiResponse deleteMissing = TalkFlow.deleteRequest(agent, NO_KEY, own.id()).send();

            assertNotFound(edit, editMissing);
            assertNotFound(delete, deleteMissing);
        }
        assertThat(TalkFlow.comments(admin, key)).as("the comments of B's issue after the requests")
                .isEqualTo(before);
    }

    // Inbox

    @Test
    @DisplayName("S4-AC-70 an assignment, a staff answer, a customer's comment, and a resolution on an issue of "
            + "company B are items for B's customer and agent, and are in no list, totalElements, or unread "
            + "count of the customer and the agent of company A")
    void inboxesOfCompanyAHoldNothingOfCompanyB() {
        Two t = two();
        String keyOfB = t.ofB().key();
        fixtures.comment(t.customerOfB(), keyOfB, Unique.text("Question"));
        flow.changeStatus(t.agentOfB(), keyOfB, "IN_PROGRESS");
        flow.changeStatus(t.agentOfB(), keyOfB, "RESOLVED");

        assertThat(kinds(TalkFlow.inboxOf(t.customerOfB(), keyOfB))).as("items of B's customer about B's issue")
                .containsExactlyInAnyOrder("STAFF_COMMENT", "ISSUE_RESOLVED");
        assertThat(kinds(TalkFlow.inboxOf(t.agentOfB(), keyOfB))).as("items of B's agent about B's issue")
                .containsExactlyInAnyOrder("ASSIGNED", "CUSTOMER_COMMENT");
        for (Session ofA : List.of(t.customerOfA(), t.agentOfA())) {
            ApiResponse page = inboxPage(ofA);
            List<JsonNode> items = List.copyOf(page.json().path("items").values());
            String who = ofA.role() + " of A";
            assertThat(issueKeys(items)).as("issues of the items of the %s", who).containsExactly(t.ofA().key());
            assertThat(page.jsonLong("totalElements")).as("totalElements of the %s", who).isEqualTo(1);
            assertThat(TalkFlow.unreadCount(ofA)).as("unread count of the %s", who).isEqualTo(1);
            assertThat(page.text()).as("the inbox answer of the %s", who)
                    .doesNotContain(keyOfB)
                    .doesNotContain(t.ofB().json().path("title").asString("no title"))
                    .doesNotContain(t.b().code());
        }
    }

    @Test
    @DisplayName("S4-AC-70, S4-AC-42 an agent of A and B who is taken off B keeps the items of A's issue alone in "
            + "the list, totalElements, and the unread count, and has B's again once assigned back")
    void agentTakenOffCompanyBKeepsOnlyTheItemsOfA() {
        Session admin = flow.admin();
        Company a = fixtures.createCompany(admin);
        Company b = fixtures.createCompany(admin);
        Session agent = fixtures.agent(admin, a.code(), b.code());
        Session colleagueOfB = fixtures.agent(admin, b.code());
        Session customerOfA = fixtures.customer(admin, a.code());
        Session customerOfB = fixtures.customer(admin, b.code());
        String keyOfA = fixtures.fileIssue(customerOfA, a.code()).key();
        String keyOfB = fixtures.fileIssue(customerOfB, b.code()).key();
        for (String key : List.of(keyOfA, keyOfB)) {
            flow.assign(admin, key, agent.accountId());
        }
        fixtures.comment(customerOfA, keyOfA, Unique.text("Question of A"));
        fixtures.comment(customerOfB, keyOfB, Unique.text("Question of B"));
        TalkFlow.mention(colleagueOfB, keyOfB, true, List.of(agent.accountId()));
        List<JsonNode> all = TalkFlow.inbox(agent);
        assertThat(kinds(TalkFlow.inboxOf(agent, keyOfA))).as("items about A's issue")
                .containsExactlyInAnyOrder("ASSIGNED", "CUSTOMER_COMMENT");
        assertThat(kinds(TalkFlow.inboxOf(agent, keyOfB))).as("items about B's issue")
                .containsExactlyInAnyOrder("ASSIGNED", "CUSTOMER_COMMENT", "MENTIONED");
        assertThat(all).as("all items of the agent").hasSize(5);
        // One item of each company is read, so the unread count tells them apart: 1 of A, 2 of B.
        TalkFlow.markRead(agent, newestItem(agent, keyOfA).path("id").asLong());
        TalkFlow.markRead(agent, newestItem(agent, keyOfB).path("id").asLong());
        assertThat(TalkFlow.unreadCount(agent)).as("unread count while assigned to both").isEqualTo(3);

        flow.unassignAgent(admin, b.code(), agent.accountId());

        ApiResponse page = inboxPage(agent);
        assertThat(issueKeys(List.copyOf(page.json().path("items").values()))).as("issues of the items once off B")
                .containsExactly(keyOfA, keyOfA);
        assertThat(page.jsonLong("totalElements")).as("totalElements once off B").isEqualTo(2);
        assertThat(TalkFlow.unreadCount(agent)).as("unread count once off B").isEqualTo(1);
        assertThat(page.text()).as("the inbox answer once off B").doesNotContain(keyOfB).doesNotContain(b.code());

        fixtures.assignAgent(admin, b.code(), agent.accountId());

        assertThat(ids(TalkFlow.inbox(agent))).as("the items once assigned back to B").isEqualTo(ids(all));
        assertThat(TalkFlow.unreadCount(agent)).as("unread count once assigned back to B").isEqualTo(3);
    }
}
