package io.popdesk.inbox;

import static io.popdesk.inbox.InboxSteps.added;
import static io.popdesk.inbox.InboxSteps.assertItem;
import static io.popdesk.inbox.InboxSteps.idsAbout;
import static io.popdesk.inbox.InboxSteps.mailAbout;
import static io.popdesk.inbox.InboxSteps.same;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.inbox.InboxSteps.Cast;
import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.IssueFlow.OnBehalf;
import io.popdesk.issue.TalkFlow;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * Who gets what ({@code design.md} 3절): each event on an issue puts one item in the inbox of its one
 * recipient, with the kind, the issue, the comment, and who did it, and sends that person a mail where
 * the table has one. Nobody else gets an item or a mail: not the person who did it, a colleague of the
 * reporter, another agent of the company, the assignee before, or an admin.
 *
 * <p>With SMTP (setup "connected"), so that mail to the wrong person would arrive. Items and mails are
 * counted as what the event added, about the one issue of the case. "No mail" is read after a later
 * mail arrived.
 */
class InboxRecipientsTest extends ConnectedAppTest {

    /** The rows of the table, and the mention added by an edit. */
    enum Event {
        STAFF_PUBLIC_COMMENT,
        CUSTOMER_COMMENT,
        STAFF_RESOLVES,
        STAFF_CLOSES,
        CUSTOMER_CLOSES,
        STAFF_REOPENS,
        CUSTOMER_REOPENS,
        ASSIGNEE_CHANGED,
        FILED_ON_BEHALF,
        MENTIONED_IN_A_NEW_COMMENT,
        MENTIONED_BY_AN_EDIT
    }

    /** What an event must have made: one item for {@code recipient}, and a mail when {@code mailed}. */
    private record Outcome(String key, Session recipient, String kind, Long commentId, Session actor,
            boolean mailed) {}

    private IssueFlow flow;
    private InboxSteps steps;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
        steps = new InboxSteps(fixtures, flow);
    }

    /** Takes the issue to where the event can happen. Gives the comment an edit will add a mention to. */
    private Comment prepare(Event event, Cast c, String key) {
        switch (event) {
            case STAFF_RESOLVES -> flow.changeStatus(c.assignee(), key, "IN_PROGRESS");
            case STAFF_REOPENS, CUSTOMER_REOPENS -> flow.close(c.reporter(), key);
            case MENTIONED_BY_AN_EDIT -> {
                return fixtures.internalComment(c.assignee(), key, Unique.text("Note"));
            }
            default -> { }
        }
        return null;
    }

    private Outcome act(Event event, Cast c, String key, Comment prepared) {
        Session staff = c.assignee();
        Session reporter = c.reporter();
        return switch (event) {
            case STAFF_PUBLIC_COMMENT -> new Outcome(key, reporter, "STAFF_COMMENT",
                    fixtures.comment(staff, key, Unique.text("Answer")).id(), staff, true);
            case CUSTOMER_COMMENT -> new Outcome(key, staff, "CUSTOMER_COMMENT",
                    fixtures.comment(reporter, key, Unique.text("Question")).id(), reporter, false);
            case STAFF_RESOLVES -> {
                flow.changeStatus(staff, key, "RESOLVED");
                yield new Outcome(key, reporter, "ISSUE_RESOLVED", null, staff, true);
            }
            case STAFF_CLOSES -> {
                flow.changeStatus(staff, key, "CLOSED");
                yield new Outcome(key, reporter, "ISSUE_CLOSED", null, staff, true);
            }
            case CUSTOMER_CLOSES -> {
                flow.close(reporter, key);
                yield new Outcome(key, staff, "ISSUE_CLOSED", null, reporter, false);
            }
            case STAFF_REOPENS -> {
                flow.reopen(staff, key);
                yield new Outcome(key, reporter, "ISSUE_REOPENED", null, staff, true);
            }
            case CUSTOMER_REOPENS -> {
                flow.reopen(reporter, key);
                yield new Outcome(key, staff, "ISSUE_REOPENED", null, reporter, false);
            }
            case ASSIGNEE_CHANGED -> {
                flow.assign(c.admin(), key, c.otherAgent().accountId());
                yield new Outcome(key, c.otherAgent(), "ASSIGNED", null, c.admin(), true);
            }
            case FILED_ON_BEHALF -> new Outcome(
                    flow.fileOnBehalf(staff, OnBehalf.of(c.code(), reporter.accountId())).key(), reporter,
                    "ISSUE_FILED_ON_BEHALF", null, staff, true);
            case MENTIONED_IN_A_NEW_COMMENT -> new Outcome(key, c.otherAgent(), "MENTIONED",
                    TalkFlow.mention(staff, key, true, List.of(c.otherAgent().accountId())).id(), staff, true);
            case MENTIONED_BY_AN_EDIT -> {
                TalkFlow.editMentions(staff, key, prepared.id(), List.of(c.otherAgent().accountId()));
                yield new Outcome(key, c.otherAgent(), "MENTIONED", prepared.id(), staff, true);
            }
        };
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Event.class)
    @DisplayName("S4-AC-27 S4-AC-28 S4-AC-29 S4-AC-30 S4-AC-31 S4-AC-32 S4-AC-33 S4-AC-34 S4-AC-58 an event puts "
            + "one item with its kind, issue, comment, and actor in the inbox of its one recipient and mails that "
            + "person where the table has a mail; the person who did it, a colleague, another agent, the assignee "
            + "before, and an admin get no item and no mail")
    void eventReachesItsOneRecipientAndNobodyElse(Event event) {
        Cast c = steps.cast();
        String filed = event == Event.FILED_ON_BEHALF ? null : steps.assignedIssue(c).key();
        Comment prepared = filed == null ? null : prepare(event, c, filed);
        steps.awaitALaterMail(c.admin());
        Map<String, Set<Long>> itemsBefore = new LinkedHashMap<>();
        Map<String, Integer> mailBefore = new LinkedHashMap<>();
        for (Session person : c.everyone()) {
            itemsBefore.put(person.email(), filed == null ? Set.of() : idsAbout(person, filed));
            mailBefore.put(person.email(), filed == null ? 0 : mailAbout(mail(), person, filed).size());
        }

        Outcome o = act(event, c, filed, prepared);

        steps.awaitALaterMail(c.admin());
        List<JsonNode> received = added(o.recipient(), o.key(), itemsBefore.get(o.recipient().email()));
        assertThat(received).as("items the event added for its recipient %s", o.recipient().email()).hasSize(1);
        assertItem(received.getFirst(), o.recipient(), o.kind(), o.key(), o.commentId(), o.actor());
        SoftAssertions softly = new SoftAssertions();
        for (Session person : c.everyone()) {
            boolean recipient = same(person, o.recipient());
            int mailed = mailAbout(mail(), person, o.key()).size() - mailBefore.get(person.email());
            softly.assertThat(mailed).as("mails about %s the event sent to %s (%s)", o.key(), person.email(),
                    person.role()).isEqualTo(recipient && o.mailed() ? 1 : 0);
            if (!recipient) {
                softly.assertThat(added(person, o.key(), itemsBefore.get(person.email())))
                        .as("items the event added for %s (%s)", person.email(), person.role()).isEmpty();
            }
        }
        softly.assertAll();
    }
}
