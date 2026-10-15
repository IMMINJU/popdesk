package io.popdesk.link;

import static io.popdesk.link.LinkSteps.LINKED_CLOSED;
import static io.popdesk.link.LinkSteps.LINKED_RESOLVED;
import static io.popdesk.link.LinkSteps.RELATED;
import static io.popdesk.link.LinkSteps.titleOf;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.link.LinkSteps.World;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Wait;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * The inbox items of linked issues (LINK-6, {@code rules.md} 10절): when an issue becomes {@code RESOLVED} or
 * {@code CLOSED}, the assignee of each issue linked to it gets one item on their own issue, with the changed issue
 * in {@code linkedIssue}; who does not get one; what a reader who cannot see both issues reads; and that nothing
 * else changes. With SMTP (setup "connected"), so that a mail would arrive. Items are counted on issues the test
 * made, and unread counts on accounts it made.
 *
 * <p>In each test the changed issue {@code X} is of company B, and the assignee's issue {@code Y} of company A.
 */
class LinkInboxTest extends ConnectedAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    /** The changed issue {@code x}, the watching issue {@code y} linked to it, and its assignee. */
    private record Linked(World w, Issue x, Issue y, Session assignee) {}

    /** {@code y} of A linked to {@code x} of B, and {@code y} assigned to a new agent assigned to A and B. */
    private Linked linked() {
        World w = LinkSteps.world(fixtures, flow);
        Issue x = w.issueOfB(fixtures);
        Issue y = w.issueOfA(fixtures);
        Session assignee = fixtures.agent(w.admin(), w.a().code(), w.b().code());
        flow.assign(w.admin(), y.key(), assignee.accountId());
        LinkSteps.link(w.linker(), y.key(), x.key(), RELATED);
        return new Linked(w, x, y, assignee);
    }

    /** The reader's items of the slice's two kinds about their issue {@code key}, newest first. */
    private static List<JsonNode> linkedItems(Session reader, String key) {
        return TalkFlow.inboxOf(reader, key).stream()
                .filter(n -> List.of(LINKED_RESOLVED, LINKED_CLOSED).contains(TalkFlow.kind(n))).toList();
    }

    /** {@code "KIND on KEY about KEY TITLE"} of an item. */
    private static String describe(JsonNode item) {
        JsonNode linked = item.path("linkedIssue");
        return TalkFlow.kind(item) + " on " + item.path("issue").path("key").asString(null) + " about "
                + linked.path("key").asString(null) + " " + linked.path("title").asString(null);
    }

    private static String expected(String kind, Issue on, Issue about, String title) {
        return kind + " on " + on.key() + " about " + about.key() + " " + title;
    }

    /** Takes the issue from where it is to {@code RESOLVED}, as staff. */
    private void resolve(Session staff, String key) {
        String status = flow.detail(staff, key).path("status").asString(null);
        if (!"IN_PROGRESS".equals(status)) {
            flow.changeStatus(staff, key, "IN_PROGRESS");
        }
        flow.changeStatus(staff, key, "RESOLVED");
    }

    /**
     * Waits until every mail and webhook queued so far is out: a later mail, and the webhook of a later issue of
     * A. So mails and webhooks are counted after this, not after a fixed wait.
     */
    private void awaitWhatWasQueued(World w) {
        TalkFlow.awaitALaterMail(fixtures, w.admin());
        String later = fixtures.fileIssue(w.reporterA(), w.a().code()).key();
        Wait.until("the webhook of the later issue " + later,
                () -> webhook().containing(later).isEmpty() ? null : Boolean.TRUE);
    }

    private static List<ReceivedMail> mailNaming(List<ReceivedMail> mails, String text) {
        return mails.stream().filter(m -> (m.subject() + "\n" + m.text()).contains(text)).toList();
    }

    // S6C-AC-31

    /** How the changed issue becomes RESOLVED or CLOSED. */
    enum Change {
        STAFF_RESOLVES,
        STAFF_CLOSES,
        CUSTOMER_CLOSES,
        RESOLVED_THEN_CLOSED
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Change.class)
    @DisplayName("S6C-AC-31 when a linked issue is resolved or closed, by staff or by its customer, the assignee of the other issue gets one LINKED_ISSUE_RESOLVED or LINKED_ISSUE_CLOSED item on their own issue with the changed issue's key and title, again for RESOLVED then CLOSED, and no mail or webhook about it")
    void assigneeOfTheLinkedIssueIsTold(Change change) {
        Linked s = linked();
        World w = s.w();
        awaitWhatWasQueued(w);
        int mailsBefore = mail().messagesTo(s.assignee().email()).size();
        int webhooksBefore = webhook().containing(s.y().key()).size();

        List<String> kinds = switch (change) {
            case STAFF_RESOLVES -> {
                flow.moveTo(w.admin(), s.x().key(), "RESOLVED");
                yield List.of(LINKED_RESOLVED);
            }
            case STAFF_CLOSES -> {
                flow.changeStatus(w.admin(), s.x().key(), "CLOSED");
                yield List.of(LINKED_CLOSED);
            }
            case CUSTOMER_CLOSES -> {
                flow.close(w.reporterB(), s.x().key());
                yield List.of(LINKED_CLOSED);
            }
            case RESOLVED_THEN_CLOSED -> {
                flow.moveTo(w.admin(), s.x().key(), "RESOLVED");
                flow.close(w.reporterB(), s.x().key());
                yield List.of(LINKED_CLOSED, LINKED_RESOLVED);
            }
        };

        assertThat(linkedItems(s.assignee(), s.y().key())).extracting(LinkInboxTest::describe)
                .as("items of the assignee of the linked issue, newest first")
                .containsExactlyElementsOf(kinds.stream().map(k -> expected(k, s.y(), s.x(), titleOf(s.x()))).toList());
        assertThat(linkedItems(s.assignee(), s.x().key())).as("items on the changed issue itself").isEmpty();
        awaitWhatWasQueued(w);
        assertThat(mail().messagesTo(s.assignee().email())).as("mail to the assignee of the linked issue")
                .hasSize(mailsBefore);
        assertThat(webhook().containing(s.y().key())).as("webhooks naming the linked issue").hasSize(webhooksBefore);
    }

    /** A change of the linked issue to a status other than RESOLVED or CLOSED. */
    enum OtherStatus {
        TO_IN_PROGRESS,
        REOPENED_AFTER_READING
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(OtherStatus.class)
    @DisplayName("S6C-AC-31 a linked issue moving to IN_PROGRESS, or reopened after the item was read, adds no item and leaves the unread count; resolving it afterwards adds one")
    void otherStatusIsNotTold(OtherStatus change) {
        Linked s = linked();
        Session admin = s.w().admin();
        if (change == OtherStatus.REOPENED_AFTER_READING) {
            flow.moveTo(admin, s.x().key(), "RESOLVED");
            List<JsonNode> items = linkedItems(s.assignee(), s.y().key());
            assertThat(items).as("items after resolving").hasSize(1);
            TalkFlow.markRead(s.assignee(), items.getFirst().path("id").asLong());
        }
        int items = linkedItems(s.assignee(), s.y().key()).size();
        long unread = TalkFlow.unreadCount(s.assignee());

        if (change == OtherStatus.TO_IN_PROGRESS) {
            flow.changeStatus(admin, s.x().key(), "IN_PROGRESS");
        } else {
            flow.reopen(admin, s.x().key());
        }

        assertThat(linkedItems(s.assignee(), s.y().key())).as("items after %s", change).hasSize(items);
        assertThat(TalkFlow.unreadCount(s.assignee())).as("unread count after %s", change).isEqualTo(unread);

        resolve(admin, s.x().key());

        assertThat(linkedItems(s.assignee(), s.y().key())).as("items after resolving then").hasSize(items + 1);
        assertThat(TalkFlow.unreadCount(s.assignee())).as("unread count after resolving then").isEqualTo(unread + 1);
    }

    // S6C-AC-32

    /** Why the assignee of the linked issue must not be told. */
    enum NotTold {
        CANNOT_SEE_THE_CHANGED_ISSUE,
        CANNOT_SEE_THEIR_OWN_ISSUE,
        NOT_ACTIVE,
        CHANGED_IT_THEMSELVES,
        NO_ASSIGNEE,
        OWN_ISSUE_CLOSED
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(NotTold.class)
    @DisplayName("S6C-AC-32 no item for an assignee who cannot see the changed issue or no longer sees their own, is not active, changed it themselves, for an issue without assignee, or whose issue is CLOSED, read after they could see both again; the assignee of another linked issue gets theirs")
    void someAssigneesAreNotTold(NotTold why) {
        World w = LinkSteps.world(fixtures, flow);
        Issue x = w.issueOfB(fixtures);
        Issue y = w.issueOfA(fixtures);
        Issue z = w.issueOfA(fixtures);
        Session other = fixtures.agent(w.admin(), w.a().code(), w.b().code());
        Session assignee = why == NotTold.CANNOT_SEE_THE_CHANGED_ISSUE
                ? fixtures.agent(w.admin(), w.a().code())
                : fixtures.agent(w.admin(), w.a().code(), w.b().code());
        flow.assign(w.admin(), y.key(), assignee.accountId());
        flow.assign(w.admin(), z.key(), other.accountId());
        LinkSteps.link(w.linker(), y.key(), x.key(), RELATED);
        LinkSteps.link(w.linker(), x.key(), z.key(), RELATED);
        Session actor = w.admin();
        switch (why) {
            case CANNOT_SEE_THE_CHANGED_ISSUE -> { }
            case CANNOT_SEE_THEIR_OWN_ISSUE -> flow.unassignAgent(w.admin(), w.a().code(), assignee.accountId());
            case NOT_ACTIVE -> flow.suspend(w.admin(), assignee.accountId());
            case CHANGED_IT_THEMSELVES -> actor = assignee;
            case NO_ASSIGNEE -> flow.unassign(w.admin(), y.key());
            case OWN_ISSUE_CLOSED -> flow.changeStatus(w.admin(), y.key(), "CLOSED");
        }

        flow.moveTo(actor, x.key(), "RESOLVED");

        Session reader = assignee;
        switch (why) {
            case CANNOT_SEE_THE_CHANGED_ISSUE -> fixtures.assignAgent(w.admin(), w.b().code(), assignee.accountId());
            case CANNOT_SEE_THEIR_OWN_ISSUE -> fixtures.assignAgent(w.admin(), w.a().code(), assignee.accountId());
            case NOT_ACTIVE -> {
                flow.reactivate(w.admin(), assignee.accountId());
                reader = fixtures.signInAgain(assignee);
            }
            default -> { }
        }
        assertThat(linkedItems(other, z.key())).extracting(LinkInboxTest::describe)
                .as("items of the assignee of the other linked issue")
                .containsExactly(expected(LINKED_RESOLVED, z, x, titleOf(x)));
        assertThat(linkedItems(reader, y.key())).as("items of the assignee who is not told").isEmpty();
        assertThat(linkedItems(reader, x.key())).as("items of that assignee on the changed issue").isEmpty();
    }

    @Test
    @DisplayName("S6C-AC-32 a person who is the assignee of two issues linked to the changed one gets one item for each")
    void oneItemForEachOwnIssue() {
        World w = LinkSteps.world(fixtures, flow);
        Issue x = w.issueOfB(fixtures);
        Issue first = w.issueOfA(fixtures);
        Issue second = w.issueOfB(fixtures);
        Session assignee = fixtures.agent(w.admin(), w.a().code(), w.b().code());
        flow.assign(w.admin(), first.key(), assignee.accountId());
        flow.assign(w.admin(), second.key(), assignee.accountId());
        LinkSteps.link(w.linker(), first.key(), x.key(), RELATED);
        LinkSteps.link(w.linker(), x.key(), second.key(), LinkSteps.DUPLICATED_BY);

        flow.changeStatus(w.admin(), x.key(), "CLOSED");

        assertThat(linkedItems(assignee, first.key())).extracting(LinkInboxTest::describe).as("items on the first")
                .containsExactly(expected(LINKED_CLOSED, first, x, titleOf(x)));
        assertThat(linkedItems(assignee, second.key())).extracting(LinkInboxTest::describe).as("items on the second")
                .containsExactly(expected(LINKED_CLOSED, second, x, titleOf(x)));
    }

    // S6C-AC-33

    @Test
    @DisplayName("S6C-AC-33 the item is listed and counted as unread only while the reader can see both issues: taken off B (the changed issue's company) it is gone from the inbox and the unread count, back when assigned again")
    void itemNeedsBothIssuesWhenRead() {
        Linked s = linked();
        flow.moveTo(s.w().admin(), s.x().key(), "RESOLVED");
        long unread = TalkFlow.unreadCount(s.assignee());
        assertThat(linkedItems(s.assignee(), s.y().key())).as("the item before").hasSize(1);

        flow.unassignAgent(s.w().admin(), s.w().b().code(), s.assignee().accountId());

        assertThat(linkedItems(s.assignee(), s.y().key())).as("items once taken off B").isEmpty();
        assertThat(TalkFlow.inboxOf(s.assignee(), s.y().key())).as("other items on the own issue stay").isNotEmpty();
        assertThat(TalkFlow.unreadCount(s.assignee())).as("unread count once taken off B").isEqualTo(unread - 1);

        fixtures.assignAgent(s.w().admin(), s.w().b().code(), s.assignee().accountId());

        assertThat(linkedItems(s.assignee(), s.y().key())).as("items once assigned to B again").hasSize(1);
        assertThat(TalkFlow.unreadCount(s.assignee())).as("unread count once assigned again").isEqualTo(unread);
    }

    @Test
    @DisplayName("S6C-AC-33 the item gives the changed issue's title as it is when read")
    void itemGivesTheTitleOfNow() {
        Linked s = linked();
        flow.moveTo(s.w().admin(), s.x().key(), "RESOLVED");
        String renamed = LinkSteps.title("Renamed");

        flow.edit(s.w().admin(), s.x().key(), Map.of("title", renamed));

        assertThat(linkedItems(s.assignee(), s.y().key())).extracting(LinkInboxTest::describe)
                .as("the item after the title changed")
                .containsExactly(expected(LINKED_RESOLVED, s.y(), s.x(), renamed));
    }

    // S6C-AC-34

    @Test
    @DisplayName("S6C-AC-34 other kinds of item have linkedIssue null, and the changed issue's own assignee and customer get the same items and mails as for an issue without links")
    void otherItemsStayAsTheyWere() {
        Linked s = linked();
        World w = s.w();
        Issue plain = w.issueOfB(fixtures);
        Session ownAssignee = fixtures.agent(w.admin(), w.b().code());
        flow.assign(w.admin(), s.x().key(), ownAssignee.accountId());
        flow.assign(w.admin(), plain.key(), ownAssignee.accountId());

        flow.moveTo(w.admin(), s.x().key(), "RESOLVED");
        flow.moveTo(w.admin(), plain.key(), "RESOLVED");
        TalkFlow.awaitALaterMail(fixtures, w.admin());

        assertThat(linkedItems(s.assignee(), s.y().key())).as("the item of the linked issue's assignee").hasSize(1);
        assertThat(TalkFlow.kinds(TalkFlow.inboxOf(ownAssignee, s.x().key())))
                .as("items of the changed issue's assignee, like for an issue without links")
                .isEqualTo(TalkFlow.kinds(TalkFlow.inboxOf(ownAssignee, plain.key()))).isNotEmpty();
        assertThat(TalkFlow.kinds(TalkFlow.inboxOf(w.reporterB(), s.x().key())))
                .as("items of the changed issue's customer, like for an issue without links")
                .isEqualTo(TalkFlow.kinds(TalkFlow.inboxOf(w.reporterB(), plain.key()))).isNotEmpty();
        List<ReceivedMail> toCustomer = mail().messagesTo(w.reporterB().email());
        assertThat(mailNaming(toCustomer, s.x().key())).as("mails to the customer about the changed issue")
                .hasSameSizeAs(mailNaming(toCustomer, plain.key()));
        List<JsonNode> others = TalkFlow.inbox(s.assignee()).stream()
                .filter(n -> !List.of(LINKED_RESOLVED, LINKED_CLOSED).contains(TalkFlow.kind(n))).toList();
        assertThat(others).as("other items of the linked issue's assignee").isNotEmpty()
                .allSatisfy(n -> assertThat(n.has("linkedIssue") && n.path("linkedIssue").isNull())
                        .as("linkedIssue is null in %s", n).isTrue());
        assertThat(TalkFlow.inboxOf(ownAssignee, s.x().key())).allSatisfy(n -> assertThat(
                n.path("linkedIssue").isNull()).as("linkedIssue is null in %s", n).isTrue());
    }

    // S6C-AC-35

    @Test
    @DisplayName("S6C-AC-35 resolving, reopening, and resolving again leaves one unread item; once it is read, the next resolution adds a new one")
    void unreadItemIsNotRepeated() {
        Linked s = linked();
        Session admin = s.w().admin();
        flow.moveTo(admin, s.x().key(), "RESOLVED");

        flow.reopen(admin, s.x().key());
        resolve(admin, s.x().key());

        List<JsonNode> once = linkedItems(s.assignee(), s.y().key());
        assertThat(once).as("items after resolving twice").hasSize(1);
        TalkFlow.markRead(s.assignee(), once.getFirst().path("id").asLong());
        long unread = TalkFlow.unreadCount(s.assignee());

        flow.reopen(admin, s.x().key());
        resolve(admin, s.x().key());

        List<JsonNode> twice = linkedItems(s.assignee(), s.y().key());
        assertThat(twice).extracting(LinkInboxTest::describe).as("items after reading and resolving again")
                .containsExactly(expected(LINKED_RESOLVED, s.y(), s.x(), titleOf(s.x())),
                        expected(LINKED_RESOLVED, s.y(), s.x(), titleOf(s.x())));
        assertThat(twice.stream().filter(n -> n.path("readAt").isNull()).count()).as("unread among them")
                .isEqualTo(1);
        assertThat(TalkFlow.unreadCount(s.assignee())).as("unread count").isEqualTo(unread + 1);
    }
}
