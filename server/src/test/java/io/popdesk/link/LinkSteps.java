package io.popdesk.link;

import io.popdesk.issue.IssueFlow;
import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.TestClock;
import io.popdesk.support.Unique;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Steps of slice 6c for issue links, through the console API only: link two issues, remove a link, read the links
 * of an issue, and the {@code LINK} entries of its history. Every step expects its one status and fails with an
 * {@link AssertionError} otherwise, so before the slice is built a test fails at the step. A method named
 * {@code ...Request} gives the same request without sending it, for a test that reads another status.
 *
 * <p>Linking and removing links are limited to 60 per account in 60 seconds (S6C-AC-09), and filing issues to 10
 * per account; the test clock stands still, so a test that does more calls {@link #nextWindow(TestClock)} in
 * between or uses accounts of its own. The first admin is shared by every test of the context, so links are made
 * by an account the test made ({@link World#linker()}).
 *
 * <p>Public, with public methods, so that other packages (the hidden E2E checks among them) can use it.
 */
public final class LinkSteps {

    public static final String DUPLICATE_OF = "DUPLICATE_OF";
    public static final String DUPLICATED_BY = "DUPLICATED_BY";
    public static final String RELATED = "RELATED";

    /** The inbox kinds of slice 6c. */
    public static final String LINKED_RESOLVED = "LINKED_ISSUE_RESOLVED";
    public static final String LINKED_CLOSED = "LINKED_ISSUE_CLOSED";

    /** Links an issue may have (S6C-AC-06). */
    public static final int LIMIT = 50;

    /** Issues one customer files in one window. */
    private static final int FILED_PER_WINDOW = 10;

    private LinkSteps() {}

    /**
     * Two companies and the staff around them (S6C {@code acceptance.md} 4절): {@code linker} is an agent
     * assigned to A and B, who makes the links; {@code agentOfA} is assigned to A only and is the one who must not
     * see B; {@code unassigned} is an agent assigned to nothing. Each company has a customer who files.
     */
    public record World(Session admin, Company a, Company b, Session reporterA, Session reporterB, Session linker,
            Session agentOfA, Session unassigned) {

        /** A new public issue of company A, with a unique title. */
        public Issue issueOfA(Fixtures fixtures) {
            return fixtures.fileIssue(reporterA, NewIssue.in(a.code()).title(title("Issue of " + a.code())));
        }

        /** A new public issue of company B, with a unique title. */
        public Issue issueOfB(Fixtures fixtures) {
            return fixtures.fileIssue(reporterB, NewIssue.in(b.code()).title(title("Issue of " + b.code())));
        }
    }

    public static World world(Fixtures fixtures, IssueFlow flow) {
        Session admin = flow.admin();
        Company a = fixtures.createCompany(admin);
        Company b = fixtures.createCompany(admin);
        return new World(admin, a, b, fixtures.customer(admin, a.code()), fixtures.customer(admin, b.code()),
                fixtures.agent(admin, a.code(), b.code()), fixtures.agent(admin, a.code()), fixtures.agent(admin));
    }

    /** A title no other test has, wrapped in {@code #...#} so that no title is part of another. */
    public static String title(String prefix) {
        return prefix + " #" + Unique.number() + "#";
    }

    /** Moves the app clock past the 60 seconds of the rate limits, so the next writes start a new window. */
    public static void nextWindow(TestClock clock) {
        clock.advance(Duration.ofSeconds(61));
    }

    /**
     * {@code count} new public issues of the customer's company, moving the clock to a new window after every
     * ten so that the filing limit does not stop them.
     */
    public static List<Issue> fileMany(Fixtures fixtures, TestClock clock, Session customer, String code,
            int count) {
        List<Issue> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (i > 0 && i % FILED_PER_WINDOW == 0) {
                nextWindow(clock);
            }
            out.add(fixtures.fileIssue(customer, NewIssue.in(code).title(title("Many of " + code))));
        }
        nextWindow(clock);
        return out;
    }

    /** A JSON body from name and value pairs, in order; a value may be null, a name left out is not sent. */
    public static Map<String, Object> body(Object... pairs) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            body.put((String) pairs[i], pairs[i + 1]);
        }
        return body;
    }

    // Paths

    public static String linksPath(String key) {
        return "/api/console/issues/" + key + "/links";
    }

    public static String linkPath(String key, String otherKey) {
        return linksPath(key) + "/" + otherKey;
    }

    // Writing

    /** {@code POST .../issues/{key}/links} with this body as it is, not sent yet. */
    public static ApiRequest createRequest(Session staff, String key, Map<String, Object> body) {
        return staff.client().post(linksPath(key)).json(body);
    }

    /** {@code POST .../issues/{key}/links} with {@code {issueKey, type}}, not sent yet. */
    public static ApiRequest createRequest(Session staff, String key, String otherKey, String type) {
        return createRequest(staff, key, body("issueKey", otherKey, "type", type));
    }

    /** Staff links the issue {@code key} to {@code otherKey}; expects 201 and gives the link as seen from {@code key}. */
    public static JsonNode link(Session staff, String key, String otherKey, String type) {
        return createRequest(staff, key, otherKey, type).expect(201).json();
    }

    /** {@code DELETE .../issues/{key}/links/{otherKey}}, not sent yet. */
    public static ApiRequest deleteRequest(Session staff, String key, String otherKey) {
        return staff.client().delete(linkPath(key, otherKey));
    }

    /** Staff removes the link between the two issues; expects 204. */
    public static void unlink(Session staff, String key, String otherKey) {
        deleteRequest(staff, key, otherKey).expect(204);
    }

    // Reading

    /** {@code GET .../issues/{key}/links}, not sent yet. */
    public static ApiRequest listRequest(Session staff, String key) {
        return staff.client().get(linksPath(key));
    }

    /** The answer of the list, {@code {items}}; expects 200. */
    public static ApiResponse list(Session staff, String key) {
        return listRequest(staff, key).expect(200);
    }

    /** The links of the issue as {@code staff} sees them, in the order of the answer. */
    public static List<JsonNode> links(Session staff, String key) {
        JsonNode items = list(staff, key).json().path("items");
        if (!items.isArray()) {
            throw new AssertionError("No items array in the links of " + key);
        }
        return new ArrayList<>(items.values());
    }

    /** {@code type} and the other issue's key of each link, as {@code "TYPE KEY"}, in the order of the answer. */
    public static List<String> described(Session staff, String key) {
        return links(staff, key).stream().map(LinkSteps::describe).toList();
    }

    /** A link as {@code "TYPE KEY"}. */
    public static String describe(JsonNode link) {
        return link.path("type").asString(null) + " " + link.path("issue").path("key").asString(null);
    }

    /** The other issue's key of each link the staff member sees, in order. */
    public static List<String> linkedKeys(Session staff, String key) {
        return links(staff, key).stream().map(l -> l.path("issue").path("key").asString(null)).toList();
    }

    /** One page of the issue's history; expects 200. */
    public static ApiResponse historyPage(Session staff, String key, int page, int size) {
        return staff.client().get("/api/console/issues/" + key + "/history").query("page", page).query("size", size)
                .expect(200);
    }

    /** Every entry of the issue's history as staff see it (up to 100), oldest first. */
    public static List<JsonNode> history(Session staff, String key) {
        JsonNode items = historyPage(staff, key, 1, 100).json().path("items");
        if (!items.isArray()) {
            throw new AssertionError("No items array in the history of " + key);
        }
        return new ArrayList<>(items.values());
    }

    /** The {@code LINK} entries of the issue's history as staff see them, oldest first. */
    public static List<JsonNode> linkEntries(Session staff, String key) {
        return history(staff, key).stream().filter(e -> "LINK".equals(e.path("field").asString(null))).toList();
    }

    /**
     * {@code oldLink} or {@code newLink} of a history entry as {@code "TYPE KEY TITLE"}, or null when the field is
     * null. Fails when the field is missing.
     */
    public static String historyLink(JsonNode entry, String field) {
        if (!entry.has(field)) {
            throw new AssertionError("No field '" + field + "' in " + entry);
        }
        JsonNode link = entry.path(field);
        if (link.isNull()) {
            return null;
        }
        return link.path("type").asString(null) + " " + link.path("issue").path("key").asString(null) + " "
                + link.path("issue").path("title").asString(null);
    }

    /** The title of an issue as it was filed. */
    public static String titleOf(Issue issue) {
        return issue.json().path("title").asString(null);
    }
}
