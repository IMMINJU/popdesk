package io.popdesk.inbox;

import static io.popdesk.inbox.InboxSteps.CONSOLE_ITEM_FIELDS;
import static io.popdesk.inbox.InboxSteps.ITEM_FIELDS;
import static io.popdesk.inbox.InboxSteps.assertActor;
import static io.popdesk.inbox.InboxSteps.assertItem;
import static io.popdesk.inbox.InboxSteps.commentId;
import static io.popdesk.inbox.InboxSteps.commentIds;
import static io.popdesk.inbox.InboxSteps.item;
import static io.popdesk.inbox.InboxSteps.itemsOf;
import static io.popdesk.inbox.InboxSteps.page;
import static io.popdesk.inbox.InboxSteps.total;
import static io.popdesk.issue.TalkFlow.NO_ITEM;
import static io.popdesk.issue.TalkFlow.assertRefused;
import static io.popdesk.issue.TalkFlow.ids;
import static io.popdesk.issue.TalkFlow.isNull;
import static io.popdesk.issue.TalkFlow.items;
import static io.popdesk.issue.TalkFlow.kinds;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.inbox.InboxSteps.Cast;
import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.manager.ManagerSteps;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * Reading an inbox on the portal and on the console: the list and its pages, the fields of an item,
 * who did it as each app shows them, the unread count, reading one item and all of them, and that a
 * person sees their own items only, and of those only the ones about issues they can see now.
 *
 * <p>Each case uses people it made itself, whose inboxes hold nothing else, so whole lists and counts
 * can be compared.
 */
class InboxReadTest extends BareAppTest {

    /** The app whose inbox a case reads. */
    enum Side {
        PORTAL,
        CONSOLE
    }

    /**
     * One issue, the person whose inbox is read, and the person whose comments are items for them: on
     * the portal the reporter and an agent, on the console the assignee and the reporter. {@code peer}
     * is another person of the reader's app who sees the same issue.
     */
    private record Desk(Cast c, Issue issue, Session reader, Session causer, Session peer) {

        String key() {
            return issue.key();
        }
    }

    private IssueFlow flow;
    private InboxSteps steps;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
        steps = new InboxSteps(fixtures, flow);
    }

    private Desk desk(Side side) {
        Cast c = steps.cast();
        Issue issue = fixtures.fileIssue(c.reporter(), c.code());
        flow.assign(c.admin(), issue.key(), c.assignee().accountId());
        return side == Side.PORTAL ? new Desk(c, issue, c.reporter(), c.assignee(), c.colleague())
                : new Desk(c, issue, c.assignee(), c.reporter(), c.otherAgent());
    }

    /** One more item for the reader, a second later on the app's clock: a comment of the causer. */
    private Comment more(Desk d) {
        flow.tick();
        return fixtures.comment(d.causer(), d.key(), Unique.text("Comment"));
    }

    /** The reader's item about this comment. Fails when there is not exactly one. */
    private static JsonNode itemAbout(Desk d, Comment comment) {
        List<JsonNode> found = TalkFlow.inboxOf(d.reader(), d.key()).stream()
                .filter(n -> Long.valueOf(comment.id()).equals(commentId(n))).toList();
        assertThat(found).as("items of %s about comment %s", d.reader().email(), comment.id()).hasSize(1);
        return found.getFirst();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    // S4-AC-39

    @ParameterizedTest(name = "{0}")
    @EnumSource(Side.class)
    @DisplayName("S4-AC-39 the inbox lists the caller's items newest first, in pages with page, size, and "
            + "totalElements")
    void listIsNewestFirstInPages(Side side) {
        Desk d = desk(side);
        List<Long> earlier = ids(TalkFlow.inbox(d.reader()));
        Comment first = more(d);
        Comment second = more(d);
        Comment third = more(d);

        List<JsonNode> all = TalkFlow.inbox(d.reader());
        ApiResponse pageOne = page(d.reader(), 1, 2);
        ApiResponse pageTwo = page(d.reader(), 2, 2);

        assertThat(all).as("items of %s", d.reader().email()).hasSize(earlier.size() + 3);
        assertThat(commentIds(all.subList(0, 3))).as("comments of the three newest items, newest first")
                .containsExactly(third.id(), second.id(), first.id());
        assertThat(ids(all.subList(3, all.size()))).as("the items from before, after the new ones").isEqualTo(earlier);
        assertThat(ids(items(pageOne))).as("page 1 of size 2").isEqualTo(ids(all).subList(0, 2));
        assertThat(ids(items(pageTwo))).as("page 2 of size 2")
                .isEqualTo(ids(all).subList(2, Math.min(4, all.size())));
        for (ApiResponse response : List.of(pageOne, pageTwo)) {
            assertThat(response.jsonLong("totalElements")).as("totalElements").isEqualTo(all.size());
            assertThat(response.jsonLong("size")).as("size").isEqualTo(2);
            assertThat(response.jsonLong("totalPages")).as("totalPages").isEqualTo((all.size() + 1) / 2);
        }
        assertThat(pageTwo.jsonLong("page")).as("page").isEqualTo(2);
    }

    // S4-AC-40

    @ParameterizedTest(name = "{0}")
    @EnumSource(Side.class)
    @DisplayName("S4-AC-40 an item carries kind, issue key and title, company code, comment ID, actor, creation "
            + "time, and a null read time; after the issue is renamed it carries the new title")
    void itemCarriesItsFieldsAndTheTitleOfNow(Side side) {
        Desk d = desk(side);
        Comment comment = more(d);
        Instant made = now();

        JsonNode item = itemAbout(d, comment);

        assertThat(new TreeSet<>(item.propertyNames())).as("fields of %s", item).isEqualTo(new TreeSet<>(side == Side.PORTAL ? ITEM_FIELDS : CONSOLE_ITEM_FIELDS));
        assertItem(item, d.reader(), side == Side.PORTAL ? "STAFF_COMMENT" : "CUSTOMER_COMMENT", d.key(),
                comment.id(), d.causer());
        assertThat(new TreeSet<>(item.path("issue").propertyNames())).as("fields of issue in %s", item)
                .isEqualTo(new TreeSet<>(Set.of("key", "title")));
        assertThat(item.path("issue").path("title").asString(null)).as("issue.title")
                .isEqualTo(d.issue().json().path("title").asString(""));
        assertThat(item.path("companyCode").asString(null)).as("companyCode").isEqualTo(d.c().code());
        assertThat(IssueFlow.time(item, "createdAt")).as("createdAt").isEqualTo(made);
        assertThat(isNull(item, "readAt")).as("readAt is null in %s", item).isTrue();

        String renamed = Unique.text("Renamed");
        flow.edit(d.c().admin(), d.key(), Map.of("title", renamed));
        assertThat(item(d.reader(), item.path("id").asLong()).path("issue").path("title").asString(null))
                .as("issue.title after the issue was renamed").isEqualTo(renamed);
    }

    // S4-AC-41

    @Test
    @DisplayName("S4-AC-41 the portal gives staff who did something as the support team: the whole inbox answer "
            + "holds no name, email, or account ID of the agent and the admin")
    void portalNamesNoStaff() {
        Cast c = steps.cast();
        String key = steps.assignedIssue(c).key();
        fixtures.comment(c.assignee(), key, Unique.text("Answer"));
        flow.changeStatus(c.bystander(), key, "IN_PROGRESS");
        flow.changeStatus(c.bystander(), key, "RESOLVED");
        flow.reopen(c.assignee(), key);

        ApiResponse inbox = page(c.reporter(), 1, 100);

        assertThat(kinds(items(inbox))).as("kinds of the reporter's items")
                .containsExactlyInAnyOrder("STAFF_COMMENT", "ISSUE_RESOLVED", "ISSUE_REOPENED");
        assertThat(items(inbox)).allSatisfy(n -> assertActor(n.path("actor"), c.reporter(), c.assignee()));
        SoftAssertions softly = new SoftAssertions();
        for (Session staff : List.of(c.assignee(), c.bystander())) {
            softly.assertThat(inbox.text()).as("name of %s", staff.role()).doesNotContainIgnoringCase(staff.name());
            softly.assertThat(inbox.text()).as("email of %s", staff.role()).doesNotContainIgnoringCase(staff.email());
            softly.assertThat(TalkFlow.placesOfNumber(inbox.json(), staff.accountId(),
                            Set.of("id", "commentId", "page", "size", "totalElements", "totalPages")))
                    .as("places of account ID %s of %s", staff.accountId(), staff.role()).isEmpty();
        }
        softly.assertAll();
    }

    @Test
    @DisplayName("S4-AC-41 the console gives who did it as account ID, name, and role: the admin who assigned, "
            + "the customer who commented")
    void consoleNamesTheActor() {
        Cast c = steps.cast();
        String key = fixtures.fileIssue(c.reporter(), c.code()).key();
        flow.assign(c.bystander(), key, c.assignee().accountId());
        Comment question = fixtures.comment(c.reporter(), key, Unique.text("Question"));

        List<JsonNode> assigned = itemsOf(c.assignee(), key, "ASSIGNED");
        List<JsonNode> commented = itemsOf(c.assignee(), key, "CUSTOMER_COMMENT");

        assertThat(assigned).as("ASSIGNED items").hasSize(1);
        assertItem(assigned.getFirst(), c.assignee(), "ASSIGNED", key, null, c.bystander());
        assertThat(commented).as("CUSTOMER_COMMENT items").hasSize(1);
        assertItem(commented.getFirst(), c.assignee(), "CUSTOMER_COMMENT", key, question.id(), c.reporter());
    }

    // S4-AC-42, S4-AC-26

    @Test
    @DisplayName("S4-AC-42 S4-AC-26 a mentioned agent taken off the company loses the item of its issue from the "
            + "list, totalElements, and the unread count, and has it again once assigned back; the comment still "
            + "names them as mentioned meanwhile")
    void itemOfAnIssueOutOfScopeIsHiddenUntilItIsBack() {
        Cast c = steps.cast();
        Session agent = fixtures.agent(c.admin(), c.code());
        String key = fixtures.fileIssue(c.reporter(), c.code()).key();
        Comment mentioning = TalkFlow.mention(c.assignee(), key, true, List.of(agent.accountId()));
        List<JsonNode> before = TalkFlow.inbox(agent);
        assertThat(kinds(before)).as("the agent's items").containsExactly("MENTIONED");
        assertThat(total(agent)).as("totalElements").isEqualTo(1);
        assertThat(TalkFlow.unreadCount(agent)).as("unread count").isEqualTo(1);

        flow.unassignAgent(c.admin(), c.code(), agent.accountId());

        assertThat(TalkFlow.inbox(agent)).as("the agent's items once off the company").isEmpty();
        assertThat(total(agent)).as("totalElements once off the company").isZero();
        assertThat(TalkFlow.unreadCount(agent)).as("unread count once off the company").isZero();
        assertThat(TalkFlow.accountIds(TalkFlow.comment(c.assignee(), key, mentioning.id()).path("mentions")))
                .as("mentions of the comment while the agent is off the company").contains(agent.accountId());

        fixtures.assignAgent(c.admin(), c.code(), agent.accountId());

        assertThat(ids(TalkFlow.inbox(agent))).as("the agent's items once assigned back").isEqualTo(ids(before));
        assertThat(total(agent)).as("totalElements once assigned back").isEqualTo(1);
        assertThat(TalkFlow.unreadCount(agent)).as("unread count once assigned back").isEqualTo(1);
    }

    // S4-AC-43

    @ParameterizedTest(name = "{0}")
    @EnumSource(Side.class)
    @DisplayName("S4-AC-43 the unread count is 0 for a new account, 2 after two events, and 1 once one is read")
    void unreadCountCountsUnreadItems(Side side) {
        Cast c = steps.cast();
        Session reader = side == Side.PORTAL ? c.reporter() : c.assignee();
        assertThat(TalkFlow.unreadCount(reader)).as("unread count of a new account").isZero();
        String key = fixtures.fileIssue(c.reporter(), c.code()).key();

        if (side == Side.PORTAL) {
            fixtures.comment(c.otherAgent(), key, Unique.text("Answer"));
            fixtures.comment(c.otherAgent(), key, Unique.text("Answer"));
        } else {
            flow.assign(c.admin(), key, reader.accountId());
            fixtures.comment(c.reporter(), key, Unique.text("Question"));
        }
        assertThat(TalkFlow.unreadCount(reader)).as("unread count after two events").isEqualTo(2);
        TalkFlow.markRead(reader, TalkFlow.inbox(reader).getFirst().path("id").asLong());

        assertThat(TalkFlow.unreadCount(reader)).as("unread count after reading one").isEqualTo(1);
    }

    // S4-AC-44

    @ParameterizedTest(name = "{0}")
    @EnumSource(Side.class)
    @DisplayName("S4-AC-44 reading an item leaves the read time, and reading it again is 204 and keeps that time")
    void readingAnItemStampsItOnce(Side side) {
        Desk d = desk(side);
        long id = itemAbout(d, more(d)).path("id").asLong();
        flow.tick();
        Instant readAt = now();

        TalkFlow.markRead(d.reader(), id);
        assertThat(IssueFlow.time(item(d.reader(), id), "readAt")).as("readAt").isEqualTo(readAt);
        clock.advance(Duration.ofMinutes(5));
        TalkFlow.markRead(d.reader(), id);

        assertThat(IssueFlow.time(item(d.reader(), id), "readAt")).as("readAt after reading again").isEqualTo(readAt);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Side.class)
    @DisplayName("S4-AC-44 S4-AC-47 reading another person's item is, for someone who sees the same issue, the "
            + "404 of an item ID nobody has, and the item stays as it was, unread")
    void readingAnotherPersonsItemIsNotFound(Side side) {
        Desk d = desk(side);
        // Reading an item of their own works, so the 404 below is about whose item it is.
        TalkFlow.markRead(d.reader(), itemAbout(d, more(d)).path("id").asLong());
        JsonNode before = itemAbout(d, more(d));
        long id = before.path("id").asLong();
        long unread = TalkFlow.unreadCount(d.reader());
        assertThat(isNull(before, "readAt")).as("the item is unread: %s", before).isTrue();

        ApiResponse others = TalkFlow.markReadRequest(d.peer(), id).send();
        ApiResponse nobodys = TalkFlow.markReadRequest(d.peer(), NO_ITEM).send();
        ApiResponse ownersMissing = TalkFlow.markReadRequest(d.reader(), NO_ITEM).send();

        assertRefused(others, 404, "NOT_FOUND");
        assertRefused(nobodys, 404, "NOT_FOUND");
        assertRefused(ownersMissing, 404, "NOT_FOUND");
        assertThat(others.text()).as("body of %s, like for an item ID nobody has", others.request())
                .isEqualTo(nobodys.text());
        assertThat(item(d.reader(), id)).as("the item after the request").isEqualTo(before);
        assertThat(TalkFlow.unreadCount(d.reader())).as("unread count of the item's owner").isEqualTo(unread);
    }

    // S4-AC-45

    @ParameterizedTest(name = "{0}")
    @EnumSource(Side.class)
    @DisplayName("S4-AC-45 reading all makes the caller's unread count 0 and leaves another person's items "
            + "unread; with nothing unread it is still 204")
    void readingAllReadsOwnItemsOnly(Side side) {
        Desk d = desk(side);
        more(d);
        more(d);
        if (side == Side.PORTAL) {
            Issue peers = fixtures.fileIssue(d.peer(), d.c().code());
            fixtures.comment(d.causer(), peers.key(), Unique.text("Answer"));
        } else {
            flow.assign(d.c().admin(), fixtures.fileIssue(d.causer(), d.c().code()).key(), d.peer().accountId());
        }
        long peersUnread = TalkFlow.unreadCount(d.peer());
        assertThat(peersUnread).as("unread count of the other person").isEqualTo(1);
        assertThat(TalkFlow.unreadCount(d.reader())).as("unread count before").isGreaterThanOrEqualTo(2);

        TalkFlow.markAllRead(d.reader());

        assertThat(TalkFlow.unreadCount(d.reader())).as("unread count after reading all").isZero();
        assertThat(TalkFlow.inbox(d.reader())).as("the caller's items")
                .allSatisfy(n -> assertThat(isNull(n, "readAt")).as("readAt is null in %s", n).isFalse());
        assertThat(TalkFlow.unreadCount(d.peer())).as("unread count of the other person").isEqualTo(peersUnread);
        assertThat(TalkFlow.inbox(d.peer())).as("the other person's items")
                .allSatisfy(n -> assertThat(isNull(n, "readAt")).as("readAt is null in %s", n).isTrue());
        TalkFlow.markAllRead(d.reader());
        assertThat(TalkFlow.unreadCount(d.reader())).as("unread count after reading all again").isZero();
    }

    @Test
    @DisplayName("S4-AC-45 reading all also reads the items the caller cannot see now: back on the company, the "
            + "item has a read time")
    void readingAllReadsHiddenItemsToo() {
        Cast c = steps.cast();
        Session agent = fixtures.agent(c.admin(), c.code());
        String key = fixtures.fileIssue(c.reporter(), c.code()).key();
        flow.assign(c.admin(), key, agent.accountId());
        assertThat(TalkFlow.unreadCount(agent)).as("unread count before").isEqualTo(1);
        flow.unassignAgent(c.admin(), c.code(), agent.accountId());

        TalkFlow.markAllRead(agent);
        fixtures.assignAgent(c.admin(), c.code(), agent.accountId());

        List<JsonNode> back = TalkFlow.inboxOf(agent, key);
        assertThat(back).as("the agent's items once assigned back").hasSize(1);
        assertThat(isNull(back.getFirst(), "readAt")).as("readAt is null in %s", back.getFirst()).isFalse();
        assertThat(TalkFlow.unreadCount(agent)).as("unread count once assigned back").isZero();
    }

    // S4-AC-46

    @Test
    @DisplayName("S4-AC-46 with a membership request, an assignment, and a customer's comment in the inboxes of "
            + "staff, the inboxes of the customers hold only the staff answer: no DELIVERY_FAILED, "
            + "MEMBERSHIP_REQUESTED, ASSIGNED, or CUSTOMER_COMMENT")
    void customersGetNoStaffKinds() {
        Cast c = steps.cast();
        Company elsewhere = fixtures.createCompany(c.admin());
        Session target = fixtures.customer(c.admin(), elsewhere.code());
        ManagerSteps.mark(c.admin(), c.code(), c.colleague().accountId());
        ManagerSteps.invited(c.colleague(), c.code(), target.email(), Unique.text("Typed"));
        String key = steps.assignedIssue(c).key();
        fixtures.comment(c.reporter(), key, Unique.text("Question"));
        Comment answer = fixtures.comment(c.assignee(), key, Unique.text("Answer"));

        Wait.untilAsserted("the membership request in an admin's inbox", () -> assertThat(kinds(TalkFlow.inbox(
                c.bystander()))).as("kinds in an admin's inbox").contains("MEMBERSHIP_REQUESTED"));
        assertThat(kinds(TalkFlow.inboxOf(c.assignee(), key))).as("kinds of the assignee's items")
                .containsExactlyInAnyOrder("ASSIGNED", "CUSTOMER_COMMENT");
        List<JsonNode> reporters = TalkFlow.inbox(c.reporter());
        assertThat(reporters).as("the reporter's inbox").hasSize(1);
        assertItem(reporters.getFirst(), c.reporter(), "STAFF_COMMENT", key, answer.id(), c.assignee());
        assertThat(TalkFlow.inbox(c.colleague())).as("the inbox of the manager who asked").isEmpty();
        assertThat(TalkFlow.inbox(target)).as("the inbox of the customer the request is about").isEmpty();
    }

    // S4-AC-47

    @Test
    @DisplayName("S4-AC-47 a colleague and another agent who see the same issue have none of the reporter's and "
            + "the assignee's items")
    void anItemIsForOnePersonOnly() {
        Cast c = steps.cast();
        String key = steps.assignedIssue(c).key();
        fixtures.comment(c.assignee(), key, Unique.text("Answer"));
        fixtures.comment(c.reporter(), key, Unique.text("Question"));

        assertThat(kinds(TalkFlow.inboxOf(c.reporter(), key))).as("the reporter's items").containsExactly("STAFF_COMMENT");
        assertThat(kinds(TalkFlow.inboxOf(c.assignee(), key))).as("the assignee's items")
                .containsExactlyInAnyOrder("ASSIGNED", "CUSTOMER_COMMENT");
        for (Session onlooker : List.of(c.colleague(), c.otherAgent())) {
            flow.detail(onlooker, key);
            assertThat(TalkFlow.inbox(onlooker)).as("the inbox of %s (%s), who sees the issue", onlooker.email(),
                    onlooker.role()).isEmpty();
            assertThat(total(onlooker)).as("totalElements of %s", onlooker.email()).isZero();
            assertThat(TalkFlow.unreadCount(onlooker)).as("unread count of %s", onlooker.email()).isZero();
        }
    }

    // S4-AC-16

    /** Whose comment made the item, whether its recipient read it, and who deletes the comment. */
    enum Deleted {
        STAFF_ANSWER_BY_ITS_AUTHOR,
        MENTION_BY_ITS_AUTHOR,
        CUSTOMER_COMMENT_BY_ITS_AUTHOR,
        STAFF_ANSWER_ALREADY_READ,
        STAFF_ANSWER_BY_AN_ADMIN
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Deleted.class)
    @DisplayName("S4-AC-16 deleting a comment takes the item it made out of the recipient's list, totalElements, "
            + "and unread count: a staff answer, a mention, a customer's comment, an item already read, and a "
            + "comment an admin deletes")
    void deletingTheCommentRemovesItsItem(Deleted deleted) {
        Cast c = steps.cast();
        String key = steps.assignedIssue(c).key();
        Session author = deleted == Deleted.CUSTOMER_COMMENT_BY_ITS_AUTHOR ? c.reporter() : c.assignee();
        Session reader = switch (deleted) {
            case MENTION_BY_ITS_AUTHOR -> c.otherAgent();
            case CUSTOMER_COMMENT_BY_ITS_AUTHOR -> c.assignee();
            default -> c.reporter();
        };
        String kind = switch (deleted) {
            case MENTION_BY_ITS_AUTHOR -> "MENTIONED";
            case CUSTOMER_COMMENT_BY_ITS_AUTHOR -> "CUSTOMER_COMMENT";
            default -> "STAFF_COMMENT";
        };
        Comment comment = deleted == Deleted.MENTION_BY_ITS_AUTHOR
                ? TalkFlow.mention(author, key, true, List.of(reader.accountId()))
                : fixtures.comment(author, key, Unique.text("Comment"));
        List<JsonNode> made = TalkFlow.inbox(reader).stream()
                .filter(n -> Long.valueOf(comment.id()).equals(commentId(n))).toList();
        assertThat(made).as("items of %s about comment %s", reader.email(), comment.id()).hasSize(1);
        assertItem(made.getFirst(), reader, kind, key, comment.id(), author);
        long itemId = made.getFirst().path("id").asLong();
        boolean read = deleted == Deleted.STAFF_ANSWER_ALREADY_READ;
        if (read) {
            TalkFlow.markRead(reader, itemId);
        }
        List<Long> before = ids(TalkFlow.inbox(reader));
        long unreadBefore = TalkFlow.unreadCount(reader);
        assertThat(unreadBefore).as("unread count before").isEqualTo(read ? before.size() - 1 : before.size());

        TalkFlow.delete(deleted == Deleted.STAFF_ANSWER_BY_AN_ADMIN ? c.bystander() : author, key, comment.id());

        List<Long> kept = before.stream().filter(id -> id != itemId).toList();
        assertThat(ids(TalkFlow.inbox(reader))).as("the recipient's items after the comment was deleted")
                .isEqualTo(kept);
        assertThat(total(reader)).as("totalElements after").isEqualTo(kept.size());
        assertThat(TalkFlow.unreadCount(reader)).as("unread count after")
                .isEqualTo(read ? unreadBefore : unreadBefore - 1);
    }
}
