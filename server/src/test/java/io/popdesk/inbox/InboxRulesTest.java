package io.popdesk.inbox;

import static io.popdesk.inbox.InboxSteps.assertError;
import static io.popdesk.inbox.InboxSteps.assertItem;
import static io.popdesk.inbox.InboxSteps.commentIds;
import static io.popdesk.inbox.InboxSteps.idsAbout;
import static io.popdesk.inbox.InboxSteps.itemsOf;
import static io.popdesk.inbox.InboxSteps.kinds;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.inbox.InboxSteps.Cast;
import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.IssueFlow.OnBehalf;
import io.popdesk.issue.TalkFlow;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Concurrently;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * When an event puts nothing in an inbox: the kinds of event the table leaves out, the person's own
 * doing, a recipient who is not {@code ACTIVE} or cannot see the issue at that moment, a request that
 * failed or changed nothing. Each case also makes an item the usual way, so that it fails while the
 * inbox is not built, and counts only the items about its own issue.
 */
class InboxRulesTest extends BareAppTest {

    private IssueFlow flow;
    private InboxSteps steps;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
        steps = new InboxSteps(fixtures, flow);
    }

    // S4-AC-27

    @Test
    @DisplayName("S4-AC-27 an internal comment puts nothing in the reporter's inbox; the public answer after it "
            + "puts one item there")
    void internalCommentIsNoItemForTheReporter() {
        Cast c = steps.cast();
        String key = steps.assignedIssue(c).key();

        fixtures.internalComment(c.assignee(), key, Unique.text("Note"));
        Comment answer = fixtures.comment(c.assignee(), key, Unique.text("Answer"));

        List<JsonNode> items = TalkFlow.inboxOf(c.reporter(), key);
        assertThat(items).as("the reporter's items about %s", key).hasSize(1);
        assertItem(items.getFirst(), c.reporter(), "STAFF_COMMENT", key, answer.id(), c.assignee());
    }

    // S4-AC-29

    @Test
    @DisplayName("S4-AC-29 going to CONFIRMED and to IN_PROGRESS puts nothing in the reporter's inbox; going to "
            + "RESOLVED after them puts one item there")
    void confirmedAndInProgressAreNoItems() {
        Cast c = steps.cast();
        String key = steps.assignedIssue(c).key();

        flow.changeStatus(c.assignee(), key, "CONFIRMED");
        flow.changeStatus(c.assignee(), key, "IN_PROGRESS");
        flow.changeStatus(c.assignee(), key, "RESOLVED");

        List<JsonNode> items = TalkFlow.inboxOf(c.reporter(), key);
        assertThat(items).as("the reporter's items about %s", key).hasSize(1);
        assertItem(items.getFirst(), c.reporter(), "ISSUE_RESOLVED", key, null, c.assignee());
    }

    // S4-AC-31

    @Test
    @DisplayName("S4-AC-31 a change of assignee is an item for the new assignee only; clearing the assignee, and "
            + "suspending the assignee with clearAssignments=true, are items for nobody")
    void clearingTheAssigneeIsNoItem() {
        Cast c = steps.cast();
        String key = fixtures.fileIssue(c.reporter(), c.code()).key();

        flow.assign(c.admin(), key, c.former().accountId());
        flow.assign(c.admin(), key, c.assignee().accountId());
        assertThat(kinds(TalkFlow.inboxOf(c.assignee(), key))).as("items of the new assignee").containsExactly("ASSIGNED");
        assertThat(kinds(TalkFlow.inboxOf(c.former(), key))).as("items of the assignee before, after the change")
                .containsExactly("ASSIGNED");
        flow.unassign(c.admin(), key);
        flow.assign(c.admin(), key, c.otherAgent().accountId());
        IssueFlow.suspendRequest(c.admin(), c.otherAgent().accountId()).query("clearAssignments", true).expect(200);
        assertThat(flow.detail(c.admin(), key).path("assignee").isNull()).as("the assignee was cleared").isTrue();
        flow.reactivate(c.admin(), c.otherAgent().accountId());
        Session otherAgain = fixtures.signInAgain(c.otherAgent());

        for (Session agent : List.of(c.former(), c.assignee(), otherAgain)) {
            List<JsonNode> items = TalkFlow.inboxOf(agent, key);
            assertThat(items).as("items of %s, who was made the assignee once", agent.email()).hasSize(1);
            assertItem(items.getFirst(), agent, "ASSIGNED", key, null, c.admin());
        }
        for (Session other : List.of(c.reporter(), c.colleague(), c.bystander(), c.admin())) {
            assertThat(TalkFlow.inboxOf(other, key)).as("items of %s (%s)", other.email(), other.role()).isEmpty();
        }
    }

    // S4-AC-34

    @Test
    @DisplayName("S4-AC-34 an agent who makes themself the assignee gets no item, and their public comment on "
            + "their own issue is an item for the reporter only")
    void ownDoingIsNoItem() {
        Cast c = steps.cast();
        String key = fixtures.fileIssue(c.reporter(), c.code()).key();

        flow.assign(c.assignee(), key, c.assignee().accountId());
        Comment answer = fixtures.comment(c.assignee(), key, Unique.text("Answer"));

        List<JsonNode> items = TalkFlow.inboxOf(c.reporter(), key);
        assertThat(items).as("the reporter's items about %s", key).hasSize(1);
        assertItem(items.getFirst(), c.reporter(), "STAFF_COMMENT", key, answer.id(), c.assignee());
        assertThat(TalkFlow.inboxOf(c.assignee(), key)).as("items of the agent who assigned themself and answered")
                .isEmpty();
    }

    // S4-AC-35

    @Test
    @DisplayName("S4-AC-35 a public comment that mentions the reporter is one MENTIONED item for the reporter, "
            + "and no STAFF_COMMENT item next to it")
    void mentionAndCommentTogetherAreOneItem() {
        Cast c = steps.cast();
        String key = steps.assignedIssue(c).key();

        Comment comment = TalkFlow.mention(c.assignee(), key, false, List.of(c.reporter().accountId()));

        List<JsonNode> items = TalkFlow.inboxOf(c.reporter(), key);
        assertThat(items).as("the reporter's items about %s", key).hasSize(1);
        assertItem(items.getFirst(), c.reporter(), "MENTIONED", key, comment.id(), c.assignee());
    }

    // S4-AC-36

    /** How the assignee stops being someone to notify, and comes back. */
    enum Away {
        SUSPENDED,
        TAKEN_OFF_THE_COMPANY
    }

    @ParameterizedTest(name = "the assignee is {0}")
    @EnumSource(Away.class)
    @DisplayName("S4-AC-36 a customer's comment while the assignee is suspended or off the company is no item for "
            + "them, also once they are back; the comment before it is")
    void assigneeWhoIsAwayGetsNoItem(Away away) {
        Cast c = steps.cast();
        String key = steps.assignedIssue(c).key();
        long assignee = c.assignee().accountId();
        Comment first = fixtures.comment(c.reporter(), key, Unique.text("First"));
        assertThat(commentIds(itemsOf(c.assignee(), key, "CUSTOMER_COMMENT"))).as("comment items before")
                .containsExactly(first.id());

        if (away == Away.SUSPENDED) {
            flow.suspend(c.admin(), assignee);
        } else {
            flow.unassignAgent(c.admin(), c.code(), assignee);
        }
        assertThat(flow.detail(c.admin(), key).path("assignee").path("accountId").asLong(-1))
                .as("the assignee of the issue while they are away").isEqualTo(assignee);
        fixtures.comment(c.reporter(), key, Unique.text("Second"));
        if (away == Away.SUSPENDED) {
            flow.reactivate(c.admin(), assignee);
        } else {
            fixtures.assignAgent(c.admin(), c.code(), assignee);
        }

        assertThat(commentIds(itemsOf(fixtures.signInAgain(c.assignee()), key, "CUSTOMER_COMMENT")))
                .as("comment items of the assignee who is back").containsExactly(first.id());
    }

    @Test
    @DisplayName("S4-AC-36 a customer's comment on an issue without an assignee is an item for nobody; once there "
            + "is an assignee, the next comment is an item for them alone")
    void commentWithoutAnAssigneeIsNoItem() {
        Cast c = steps.cast();
        String key = fixtures.fileIssue(c.reporter(), c.code()).key();

        fixtures.comment(c.reporter(), key, Unique.text("Nobody's"));
        flow.assign(c.admin(), key, c.assignee().accountId());
        Comment second = fixtures.comment(c.reporter(), key, Unique.text("Assignee's"));

        assertThat(commentIds(itemsOf(c.assignee(), key, "CUSTOMER_COMMENT"))).as("comment items of the assignee")
                .containsExactly(second.id());
        for (Session staff : List.of(c.otherAgent(), c.former(), c.bystander(), c.admin())) {
            assertThat(TalkFlow.inboxOf(staff, key)).as("items of %s (%s)", staff.email(), staff.role()).isEmpty();
        }
    }

    @Test
    @DisplayName("S4-AC-36 a staff answer while the reporter is suspended is no item for them, also once they are "
            + "back; the answer before it is")
    void suspendedReporterGetsNoItem() {
        Cast c = steps.cast();
        String key = steps.assignedIssue(c).key();
        Comment first = fixtures.comment(c.assignee(), key, Unique.text("First"));
        assertThat(commentIds(TalkFlow.inboxOf(c.reporter(), key))).as("items before").containsExactly(first.id());

        flow.suspend(c.admin(), c.reporter().accountId());
        fixtures.comment(c.assignee(), key, Unique.text("Second"));
        flow.reactivate(c.admin(), c.reporter().accountId());

        assertThat(commentIds(TalkFlow.inboxOf(fixtures.signInAgain(c.reporter()), key)))
                .as("items of the reporter who is back").containsExactly(first.id());
    }

    @Test
    @DisplayName("S4-AC-36 an admin who is the assignee gets the item of a customer's comment without being "
            + "assigned to the company")
    void adminAssigneeGetsTheItem() {
        Cast c = steps.cast();
        String key = fixtures.fileIssue(c.reporter(), c.code()).key();
        flow.assign(c.admin(), key, c.bystander().accountId());

        Comment comment = fixtures.comment(c.reporter(), key, Unique.text("Question"));

        List<JsonNode> items = itemsOf(c.bystander(), key, "CUSTOMER_COMMENT");
        assertThat(items).as("comment items of the admin who is the assignee").hasSize(1);
        assertItem(items.getFirst(), c.bystander(), "CUSTOMER_COMMENT", key, comment.id(), c.reporter());
    }

    // S4-AC-37

    @Test
    @DisplayName("S4-AC-37 requests that fail (400, 403, 404, 409, INVALID_TRANSITION) and requests that change "
            + "nothing (the same assignee, the same content) put no item in any inbox")
    void failedAndUnchangedRequestsAreNoItems() {
        Cast c = steps.cast();
        Session stranger = fixtures.agent(c.admin());
        String key = steps.assignedIssue(c).key();
        String text = Unique.text("Answer");
        Comment answer = fixtures.comment(c.assignee(), key, text);
        Comment question = fixtures.comment(c.reporter(), key, Unique.text("Question"));
        Set<Long> reporterBefore = idsAbout(c.reporter(), key);
        Set<Long> assigneeBefore = idsAbout(c.assignee(), key);
        assertThat(reporterBefore).as("the reporter's items: the answer").hasSize(1);
        assertThat(assigneeBefore).as("the assignee's items: the assignment and the question").hasSize(2);
        String idempotencyKey = "key-" + UUID.randomUUID();
        OnBehalf filing = OnBehalf.of(c.code(), c.colleague().accountId());
        flow.onBehalfRequest(c.assignee(), filing).header("Idempotency-Key", idempotencyKey).expect(201);
        assertThat(kinds(TalkFlow.inbox(c.colleague()))).as("the colleague's items after the filing for them")
                .containsExactly("ISSUE_FILED_ON_BEHALF");

        assertError(TalkFlow.addRequest(c.assignee(), key, TalkFlow.body("content", "")).send(), 400, "INVALID_INPUT");
        assertError(TalkFlow.addRequest(c.reporter(), key, TalkFlow.body("content", "")).send(), 400, "INVALID_INPUT");
        assertError(TalkFlow.editRequest(c.colleague(), key, question.id(), TalkFlow.body("content", "Not mine"))
                .send(), 403, "FORBIDDEN");
        assertError(TalkFlow.addRequest(stranger, key, TalkFlow.body("content", "Out of scope")).send(), 404,
                "NOT_FOUND");
        assertError(IssueFlow.statusRequest(c.assignee(), key, "RESOLVED").send(), 400, "INVALID_TRANSITION");
        assertError(flow.onBehalfRequest(c.assignee(), filing.title(Unique.text("Other content")))
                .header("Idempotency-Key", idempotencyKey).send(), 409, "IDEMPOTENCY_KEY_REUSED");
        flow.assign(c.admin(), key, c.assignee().accountId());
        TalkFlow.editContent(c.assignee(), key, answer.id(), text);

        assertThat(idsAbout(c.reporter(), key)).as("the reporter's items after the requests").isEqualTo(reporterBefore);
        assertThat(idsAbout(c.assignee(), key)).as("the assignee's items after the requests").isEqualTo(assigneeBefore);
        assertThat(kinds(TalkFlow.inbox(c.colleague()))).as("the colleague's items after the refused filing")
                .containsExactly("ISSUE_FILED_ON_BEHALF");
        for (Session other : List.of(c.otherAgent(), c.bystander())) {
            assertThat(TalkFlow.inboxOf(other, key)).as("items of %s (%s)", other.email(), other.role()).isEmpty();
        }
    }

    @Test
    @DisplayName("S4-AC-37 of eight requests for the same transition at once one succeeds, and the reporter has "
            + "one item")
    void sameTransitionAtOnceIsOneItem() {
        Cast c = steps.cast();
        Issue issue = steps.assignedIssue(c);
        String key = issue.key();
        flow.changeStatus(c.assignee(), key, "IN_PROGRESS");
        List<Supplier<ApiResponse>> calls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            calls.add(() -> IssueFlow.statusRequest(c.assignee(), key, "RESOLVED").send());
            calls.add(() -> IssueFlow.statusRequest(c.admin(), key, "RESOLVED").send());
        }

        List<ApiResponse> answers = Concurrently.send(8, calls);

        assertThat(answers.stream().filter(r -> r.status() == 200)).as("requests that made the transition")
                .hasSize(1);
        assertThat(kinds(TalkFlow.inboxOf(c.reporter(), key))).as("the reporter's items about %s", key)
                .containsExactly("ISSUE_RESOLVED");
    }
}
