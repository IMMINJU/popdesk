package io.popdesk.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.App;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailServer;
import io.popdesk.support.ReceivedMail;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/**
 * Steps and checks of the inbox tests of slice 4: the people around one issue, reading what an event
 * added to a person's inbox, and checking one item. Only HTTP; the requests themselves are those of
 * {@link TalkFlow} and {@link IssueFlow}.
 */
final class InboxSteps {

    /** An inbox item ID far above any the app has given out. */
    static final long NO_ITEM = 9_000_000_000_000_000L;

    /** The fields of an inbox item on either app ({@code design.md} 0절 4, the contract). */
    static final Set<String> ITEM_FIELDS = Set.of("id", "kind", "issue", "companyCode", "commentId", "actor",
            "createdAt", "readAt");

    private final Fixtures fixtures;
    private final IssueFlow flow;

    InboxSteps(Fixtures fixtures, IssueFlow flow) {
        this.fixtures = fixtures;
        this.flow = flow;
    }

    /**
     * The people around the issues of one company: the first admin, who sets things up; the customer
     * who files ({@code reporter}) and a colleague of the same company; three agents assigned to the
     * company, of whom {@code assignee} becomes the assignee, {@code former} was it before, and
     * {@code otherAgent} never is; and a second admin who does nothing.
     */
    record Cast(Session admin, Company company, Session reporter, Session colleague, Session assignee,
            Session otherAgent, Session former, Session bystander) {

        String code() {
            return company.code();
        }

        List<Session> everyone() {
            return List.of(admin, reporter, colleague, assignee, otherAgent, former, bystander);
        }
    }

    Cast cast() {
        Session admin = flow.admin();
        Company company = fixtures.createCompany(admin);
        String code = company.code();
        return new Cast(admin, company, fixtures.customer(admin, code), fixtures.customer(admin, code),
                fixtures.agent(admin, code), fixtures.agent(admin, code), fixtures.agent(admin, code),
                fixtures.admin(admin));
    }

    /** A public issue of the reporter that the first admin gave to {@code former} and then to {@code assignee}. */
    Issue assignedIssue(Cast c) {
        Issue issue = fixtures.fileIssue(c.reporter(), c.code());
        flow.assign(c.admin(), issue.key(), c.former().accountId());
        flow.assign(c.admin(), issue.key(), c.assignee().accountId());
        return issue;
    }

    /** Has an invitation mailed and waits for it: every mail queued before is out by then. */
    void awaitALaterMail(Session admin) {
        fixtures.invite(admin, Role.AGENT);
    }

    // Reading

    /** The {@code items} of a page body. */
    static List<JsonNode> items(ApiResponse page) {
        JsonNode items = page.json().path("items");
        if (!items.isArray()) {
            throw new AssertionError(page.request() + ": no items array in " + page.snippet());
        }
        return new ArrayList<>(items.values());
    }

    /** One page of the caller's inbox; expects 200. */
    static ApiResponse page(Session session, int page, int size) {
        return session.client().get(session.api("/notifications")).query("page", page).query("size", size)
                .expect(200);
    }

    /** {@code totalElements} of the caller's inbox. */
    static long total(Session session) {
        return page(session, 1, 1).jsonLong("totalElements");
    }

    static List<Long> ids(List<JsonNode> items) {
        return items.stream().map(n -> n.path("id").asLong(-1)).toList();
    }

    static String kind(JsonNode item) {
        return item.path("kind").asString(null);
    }

    static List<String> kinds(List<JsonNode> items) {
        return items.stream().map(InboxSteps::kind).toList();
    }

    /** The comment ID of an item, or null when the item is about no comment. */
    static Long commentId(JsonNode item) {
        JsonNode id = item.path("commentId");
        return id.isIntegralNumber() ? id.asLong() : null;
    }

    static List<Long> commentIds(List<JsonNode> items) {
        return items.stream().map(InboxSteps::commentId).toList();
    }

    /** The caller's items about the issue that are of this kind, newest first. */
    static List<JsonNode> itemsOf(Session session, String key, String kind) {
        return TalkFlow.inboxOf(session, key).stream().filter(n -> kind.equals(kind(n))).toList();
    }

    /** The caller's items about the issue whose ID is not in {@code before}: what came since. */
    static List<JsonNode> added(Session session, String key, Set<Long> before) {
        return TalkFlow.inboxOf(session, key).stream().filter(n -> !before.contains(n.path("id").asLong(-1)))
                .toList();
    }

    static Set<Long> idsAbout(Session session, String key) {
        return new TreeSet<>(ids(TalkFlow.inboxOf(session, key)));
    }

    /** The item with this ID in the caller's inbox. Fails when the inbox does not have it. */
    static JsonNode item(Session session, long id) {
        return TalkFlow.inbox(session).stream().filter(n -> n.path("id").asLong(-1) == id).findFirst()
                .orElseThrow(() -> new AssertionError("Item " + id + " is not in the inbox of " + session.email()));
    }

    /** Whether the field is there and null (D-44: a missing value is null, not left out). */
    static boolean isNull(JsonNode node, String field) {
        return node.has(field) && node.path(field).isNull();
    }

    // Mail

    /** Mail to the person that names the issue key in its subject or text. */
    static List<ReceivedMail> mailAbout(MailServer mail, Session who, String key) {
        return mail.messagesTo(who.email()).stream().filter(m -> (m.subject() + "\n" + m.text()).contains(key))
                .toList();
    }

    // Checks

    /** Checks kind, issue key, comment ID (null for none), and who did it, as {@code reader} gets them. */
    static void assertItem(JsonNode item, Session reader, String kind, String key, Long commentId, Session actor) {
        assertThat(kind(item)).as("kind of %s", item).isEqualTo(kind);
        assertThat(item.path("issue").path("key").asString(null)).as("issue.key of %s", item).isEqualTo(key);
        if (commentId == null) {
            assertThat(isNull(item, "commentId")).as("commentId is null in %s", item).isTrue();
        } else {
            assertThat(commentId(item)).as("commentId of %s", item).isEqualTo(commentId);
        }
        assertActor(item.path("actor"), reader, actor);
    }

    /**
     * Who did it, as the reader's app gives it: the console the person (account ID, name, role), the
     * portal a customer as a person and staff as the support team without name or account ID.
     */
    static void assertActor(JsonNode actor, Session reader, Session by) {
        if (reader.app() == App.CONSOLE) {
            assertThat(actor.path("accountId").asLong(-1)).as("actor.accountId of %s", actor)
                    .isEqualTo(by.accountId());
            assertThat(actor.path("name").asString(null)).as("actor.name of %s", actor).isEqualTo(by.name());
            assertThat(actor.path("role").asString(null)).as("actor.role of %s", actor).isEqualTo(by.role().name());
        } else if (by.role() == Role.CUSTOMER) {
            assertThat(actor.path("kind").asString(null)).as("actor.kind of %s", actor).isEqualTo("CUSTOMER");
            assertThat(actor.path("accountId").asLong(-1)).as("actor.accountId of %s", actor)
                    .isEqualTo(by.accountId());
        } else {
            assertThat(actor.path("kind").asString(null)).as("actor.kind of %s", actor).isEqualTo("SUPPORT_TEAM");
            assertThat(isNull(actor, "accountId") && isNull(actor, "name") && isNull(actor, "photoUpdatedAt"))
                    .as("accountId, name, and photoUpdatedAt are null in %s", actor).isTrue();
            assertThat(new TreeSet<>(actor.propertyNames())).as("fields of %s", actor)
                    .isEqualTo(new TreeSet<>(Set.of("kind", "accountId", "name", "photoUpdatedAt")));
        }
    }

    /** Checks status and {@code code} of an error body. */
    static void assertError(ApiResponse response, int status, String code) {
        assertThat(response.status()).as(response.request() + ": " + response.snippet()).isEqualTo(status);
        assertThat(response.errorCode()).as("code of " + response.request()).isEqualTo(code);
    }

    static boolean same(Session a, Session b) {
        return Objects.equals(a.email(), b.email());
    }
}
