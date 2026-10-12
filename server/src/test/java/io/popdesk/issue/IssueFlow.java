package io.popdesk.issue;

import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.TestClock;
import io.popdesk.support.Unique;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Steps shared by the tests of slice 3 (issues): the people of one company, issues in a given
 * status, assignees, filing on behalf, closing and reopening, editing. Only HTTP. What
 * {@link IssueSteps} already does is used from there.
 *
 * <p>Every step expects its one status (200 or 201) and fails with an {@link AssertionError}
 * otherwise, so before the slice is built a test fails at the step, on an assertion.
 *
 * <p>Public, with public methods, because tests of other packages use it (the log test in
 * {@code ops}).
 */
public final class IssueFlow {

    /**
     * The shortest way for staff from {@code RECEIVED} to each status ({@code rules.md} 2절): the
     * statuses to set one after another.
     */
    public static final Map<String, List<String>> WAY_FROM_RECEIVED = Map.of(
            "RECEIVED", List.of(),
            "CONFIRMED", List.of("CONFIRMED"),
            "IN_PROGRESS", List.of("IN_PROGRESS"),
            "RESOLVED", List.of("IN_PROGRESS", "RESOLVED"),
            "CLOSED", List.of("CLOSED"));

    /** The five statuses, in the order of the transition table. */
    public static final List<String> STATUSES = List.of("RECEIVED", "CONFIRMED", "IN_PROGRESS", "RESOLVED", "CLOSED");

    private static final String DIGITS_AS_HANGUL = "가나다라마바사아자차";

    private final Fixtures fixtures;
    private final IssueSteps steps;

    public IssueFlow(Fixtures fixtures, TestClock clock) {
        this.fixtures = fixtures;
        this.steps = new IssueSteps(fixtures, clock);
    }

    /**
     * One company and its people: the customer who files ({@code reporter}), a colleague of the same
     * company, an agent assigned to it, an agent assigned to nothing, and the first admin.
     */
    public record World(Session admin, Company company, Session reporter, Session colleague, Session agent,
            Session unassignedAgent) {

        public String code() {
            return company.code();
        }
    }

    public World world() {
        IssueSteps.Scene scene = steps.scene();
        Session colleague = fixtures.customer(scene.admin(), scene.code());
        Session agent = fixtures.agent(scene.admin(), scene.code());
        Session unassignedAgent = fixtures.agent(scene.admin());
        return new World(scene.admin(), scene.company(), scene.customer(), colleague, agent, unassignedAgent);
    }

    /** The first admin, signed in on the console, in a new sign-in window ({@link IssueSteps#admin()}). */
    public Session admin() {
        return steps.admin();
    }

    /** Moves the app clock a second on, so that the next row is later than the last one. */
    public Instant tick() {
        return steps.tick();
    }

    // Words to search for

    /** A word no other test has, in lower-case letters only: no digit, {@code %}, or {@code _}. */
    public static String word() {
        StringBuilder out = new StringBuilder("zq");
        for (char digit : Long.toString(Unique.number()).toCharArray()) {
            out.append((char) ('a' + (digit - '0')));
        }
        return out.append("kv").toString();
    }

    /** A Korean word no other test has, in Hangul syllables only. */
    public static String koreanWord() {
        StringBuilder out = new StringBuilder("결제");
        for (char digit : Long.toString(Unique.number()).toCharArray()) {
            out.append(DIGITS_AS_HANGUL.charAt(digit - '0'));
        }
        return out.append("오류").toString();
    }

    // Reading

    /**
     * The caller's issue list with these query parameters (name, value, name, value, ...), not sent
     * yet. A name may come more than once ({@code status}). No {@code size} is added.
     */
    public static ApiRequest listRequest(Session session, Object... query) {
        ApiRequest request = session.client().get(session.api("/issues"));
        for (int i = 0; i < query.length; i += 2) {
            request.query((String) query[i], query[i + 1]);
        }
        return request;
    }

    /** The first 100 issues of the caller's list narrowed by {@code query}; expects 200. */
    public static ApiResponse list(Session session, Object... query) {
        return listRequest(session, query).query("size", 100).expect(200);
    }

    /** {@code totalElements} of a page. */
    public static long total(ApiResponse page) {
        return page.jsonLong("totalElements");
    }

    /** The issue as {@code session} sees it. */
    public JsonNode detail(Session session, String key) {
        return session.client().get(session.api("/issues/" + key)).expect(200).json();
    }

    // Status

    /** Staff sets one status ({@code PUT .../status}). */
    public JsonNode changeStatus(Session staff, String key, String status) {
        return staff.client().put("/api/console/issues/" + key + "/status").json(Map.of("status", status))
                .expect(200).json();
    }

    /** Staff takes an issue that is {@code RECEIVED} to {@code status}, by {@link #WAY_FROM_RECEIVED}. */
    public void moveTo(Session staff, String key, String status) {
        List<String> way = WAY_FROM_RECEIVED.get(status);
        if (way == null) {
            throw new AssertionError("Not an issue status: " + status);
        }
        way.forEach(next -> changeStatus(staff, key, next));
    }

    /** A new issue of {@code customer} that staff took to {@code status}. */
    public Issue fileIn(Session customer, NewIssue issue, Session staff, String status) {
        Issue filed = fixtures.fileIssue(customer, issue);
        moveTo(staff, filed.key(), status);
        return filed;
    }

    /** The reporting customer closes the issue, or confirms that it is resolved. */
    public JsonNode close(Session customer, String key) {
        return customer.client().post("/api/portal/issues/" + key + "/close").expect(200).json();
    }

    /** The reporting customer or staff reopens the issue, each on their own app. */
    public JsonNode reopen(Session session, String key) {
        return session.client().post(session.api("/issues/" + key + "/reopen")).expect(200).json();
    }

    // Assignee

    public JsonNode assign(Session staff, String key, long accountId) {
        return staff.client().put("/api/console/issues/" + key + "/assignee").json(Map.of("accountId", accountId))
                .expect(200).json();
    }

    public JsonNode unassign(Session staff, String key) {
        return staff.client().delete("/api/console/issues/" + key + "/assignee").expect(200).json();
    }

    // Editing

    /**
     * Changes the given fields ({@code title}, {@code content}, {@code priority}) with the version
     * the issue has now, on the caller's app.
     */
    public JsonNode edit(Session session, String key, Map<String, Object> changes) {
        Map<String, Object> body = new LinkedHashMap<>(changes);
        body.put("version", version(session, key));
        return session.client().patch(session.api("/issues/" + key)).json(body).expect(200).json();
    }

    public long version(Session session, String key) {
        JsonNode version = detail(session, key).path("version");
        if (!version.isIntegralNumber()) {
            throw new AssertionError("No version in issue " + key);
        }
        return version.asLong();
    }

    // Filing on behalf

    /** Staff files an issue for a customer on the console. */
    public Issue fileOnBehalf(Session staff, OnBehalf issue) {
        ApiResponse response = onBehalfRequest(staff, issue).expect(201);
        return new Issue(response.jsonText("key"), issue.companyCode, response.json());
    }

    /** The request of {@link #fileOnBehalf}, not sent yet: add a header or read another status. */
    public ApiRequest onBehalfRequest(Session staff, OnBehalf issue) {
        return staff.client().post("/api/console/issues").json(issue.body());
    }

    /** What staff files for a customer. Starts with a unique title and the channel {@code PHONE}. */
    public static final class OnBehalf {

        private final String companyCode;
        private final long reporterId;
        private String channel = "PHONE";
        private String title = Unique.text("On behalf");
        private String content = "Taken down on the phone.";
        private String priority;
        private Boolean isPrivate;

        private OnBehalf(String companyCode, long reporterId) {
            this.companyCode = companyCode;
            this.reporterId = reporterId;
        }

        public static OnBehalf of(String companyCode, long reporterId) {
            return new OnBehalf(companyCode, reporterId);
        }

        public OnBehalf channel(String channel) {
            this.channel = channel;
            return this;
        }

        public OnBehalf title(String title) {
            this.title = title;
            return this;
        }

        public OnBehalf content(String content) {
            this.content = content;
            return this;
        }

        public OnBehalf priority(String priority) {
            this.priority = priority;
            return this;
        }

        public OnBehalf privateIssue() {
            this.isPrivate = true;
            return this;
        }

        public String title() {
            return title;
        }

        public Map<String, Object> body() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("companyCode", companyCode);
            body.put("reporterId", reporterId);
            body.put("channel", channel);
            body.put("title", title);
            body.put("content", content);
            if (priority != null) {
                body.put("priority", priority);
            }
            if (isPrivate != null) {
                body.put("private", isPrivate);
            }
            return body;
        }
    }

    // People (admin steps of slice 1a, whose own helpers are private to their package)

    public void suspend(Session admin, long accountId) {
        admin.client().post("/api/console/accounts/" + accountId + "/suspend").expect(200);
    }

    public void reactivate(Session admin, long accountId) {
        admin.client().post("/api/console/accounts/" + accountId + "/reactivate").expect(200);
    }

    /** Takes an agent off a company, leaving the issues assigned to them as they are. */
    public void unassignAgent(Session admin, String companyCode, long accountId) {
        admin.client().delete("/api/console/companies/" + companyCode + "/agents/" + accountId).expect(200);
    }

    /** Moves a company to {@code SUSPENDED} or {@code ARCHIVED}, or back to {@code ACTIVE}. */
    public void companyStatus(Session admin, String companyCode, String status) {
        admin.client().put("/api/console/companies/" + companyCode + "/status").json(Map.of("status", status))
                .expect(200);
    }

    /** Takes a customer out of a company. */
    public void removeCustomer(Session admin, String companyCode, long accountId) {
        admin.client().delete("/api/console/companies/" + companyCode + "/customers/" + accountId).expect(200);
    }
}
