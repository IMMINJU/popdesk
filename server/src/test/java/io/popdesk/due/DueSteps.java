package io.popdesk.due;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Steps of the due date tests of slice 6a: setting and clearing a due date, reading {@code dueDate}
 * and {@code overdue}, the installation's "today" by the app clock, and the issue list in due date
 * order. Only HTTP. What {@link IssueFlow} does (status, history, the list request) is used from there.
 *
 * <p>Every step expects 200 and fails with an {@link AssertionError} otherwise, so before the slice is
 * built a test fails at the step, on an assertion. A method named {@code ...Request} gives the same
 * request without sending it, for a test that reads another status.
 *
 * <p>"Today" is judged by the app at the time of the request, and the app's clock is the test clock
 * ({@link TestClock}), which stands still. So {@link #today(ZoneId)} is the date the app judges with,
 * and it cannot change between two requests unless the test moves the clock.
 */
public final class DueSteps {

    public static final ZoneId UTC = ZoneOffset.UTC;

    private final Fixtures fixtures;
    private final TestClock clock;
    private final IssueFlow flow;

    public DueSteps(Fixtures fixtures, TestClock clock) {
        this.fixtures = fixtures;
        this.clock = clock;
        this.flow = new IssueFlow(fixtures, clock);
    }

    public IssueFlow flow() {
        return flow;
    }

    /** An admin, a company, a customer of it, an agent assigned to it, and an agent assigned to another company. */
    public record Scene(Session admin, Company company, Session customer, Session agent, Session agentElsewhere) {

        public String code() {
            return company.code();
        }
    }

    public Scene scene() {
        Session admin = flow.admin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        Session agent = fixtures.agent(admin, company.code());
        Company elsewhere = fixtures.createCompany(admin);
        Session agentElsewhere = fixtures.agent(admin, elsewhere.code());
        return new Scene(admin, company, customer, agent, agentElsewhere);
    }

    /** A new issue of the scene's company, filed by its customer. */
    public Issue file(Scene scene) {
        return fixtures.fileIssue(scene.customer(), scene.code());
    }

    // Today

    /** The date of the app clock's instant in {@code zone}. */
    public LocalDate today(ZoneId zone) {
        return LocalDate.ofInstant(clock.instant(), zone);
    }

    /**
     * Moves the app clock forward to the next half past {@code hour} in UTC (at most a day). Used to
     * put the clock where the installation's date and the UTC date differ.
     */
    public Instant moveToUtcHour(int hour) {
        ZonedDateTime now = clock.instant().atZone(UTC);
        ZonedDateTime target = now.truncatedTo(ChronoUnit.DAYS).withHour(hour).withMinute(30);
        if (target.isBefore(now)) {
            target = target.plusDays(1);
        }
        return clock.advance(Duration.between(now, target));
    }

    // Setting and clearing

    public static String path(String key) {
        return "/api/console/issues/" + key + "/due-date";
    }

    /** {@code PUT .../due-date} with {@code {"dueDate": value}} as it is (a text, a number, null), not sent yet. */
    public static ApiRequest setRequest(Session staff, String key, Object value) {
        Map<String, Object> body = new HashMap<>();
        body.put("dueDate", value);
        return staff.client().put(path(key)).json(body);
    }

    /** Sets the due date; expects 200 and gives the issue. */
    public static JsonNode set(Session staff, String key, LocalDate date) {
        return setRequest(staff, key, date.toString()).expect(200).json();
    }

    public static ApiRequest clearRequest(Session staff, String key) {
        return staff.client().delete(path(key));
    }

    /** Clears the due date; expects 200 and gives the issue. */
    public static JsonNode clear(Session staff, String key) {
        return clearRequest(staff, key).expect(200).json();
    }

    // Reading

    /** {@code dueDate} of an issue or a list item: the text, or null when it is null. Fails when it is missing. */
    public static String dueDate(JsonNode issue) {
        JsonNode value = issue.path("dueDate");
        if (value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw new AssertionError("No dueDate (text or null) in " + issue);
        }
        return value.stringValue();
    }

    /** {@code overdue} of an issue or a list item. Fails unless it is a boolean. */
    public static boolean overdue(JsonNode issue) {
        JsonNode value = issue.path("overdue");
        if (!value.isBoolean()) {
            throw new AssertionError("No boolean overdue in " + issue);
        }
        return value.booleanValue();
    }

    /** Fails unless the issue (or list item) has this due date and this {@code overdue}. */
    public static void assertDue(JsonNode issue, LocalDate date, boolean overdue, String what) {
        assertThat(dueDate(issue)).as("dueDate of %s: %s", what, issue).isEqualTo(date == null ? null : date.toString());
        assertThat(overdue(issue)).as("overdue of %s: %s", what, issue).isEqualTo(overdue);
    }

    /** The {@code code} that {@code fields} of a 400 body gives for {@code field}, or null. */
    public static String fieldCode(ApiResponse error, String field) {
        for (JsonNode f : error.json().path("fields").values()) {
            if (field.equals(f.path("field").asString(null))) {
                return f.path("code").asString(null);
            }
        }
        return null;
    }

    /** Fails unless the response has this status and this error {@code code}. */
    public static void assertRefused(ApiResponse response, int status, String code) {
        assertThat(response.status()).as("%s: %s", response.request(), response.snippet()).isEqualTo(status);
        assertThat(response.errorCode()).as("code of %s: %s", response.request(), response.snippet())
                .isEqualTo(code);
    }

    /**
     * "Today" of the installation is {@code zone}: in a new scene an agent sets {@code today(zone)}
     * (200, not overdue) and then the day before it (400 {@code dueDate} {@code OUT_OF_RANGE}), and the
     * issue keeps the first date. The caller first puts the clock where {@code zone}'s date is not the
     * UTC date, so that an app that judges in UTC fails one of the two.
     */
    public void assertTodayIsJudgedIn(ZoneId zone) {
        Scene s = scene();
        Issue issue = file(s);
        LocalDate today = today(zone);

        JsonNode taken = set(s.agent(), issue.key(), today);
        ApiResponse dayBefore = setRequest(s.agent(), issue.key(), today.minusDays(1).toString()).send();

        assertDue(taken, today, false, "the answer for today in " + zone);
        assertRefused(dayBefore, 400, "INVALID_INPUT");
        assertThat(fieldCode(dayBefore, "dueDate")).as("code of dueDate for the day before today in %s: %s", zone,
                dayBefore.snippet()).isEqualTo("OUT_OF_RANGE");
        assertDue(flow.detail(s.agent(), issue.key()), today, false, "the issue read again");
    }

    public long version(Session session, String key) {
        return flow.version(session, key);
    }

    /** The {@code DUE_DATE} entries of the issue's history, oldest first. */
    public List<JsonNode> dueDateHistory(Session staff, String key) {
        return flow.history(staff, key).stream().filter(e -> "DUE_DATE".equals(e.path("field").asString(null)))
                .toList();
    }

    // The list

    /** The items of a page body. */
    public static List<JsonNode> items(ApiResponse page) {
        JsonNode items = page.json().path("items");
        if (!items.isArray()) {
            throw new AssertionError(page.request() + ": no items array in " + page.snippet());
        }
        return new ArrayList<>(items.values());
    }

    /** Keys of the issues on a page, in page order. */
    public static List<String> keys(ApiResponse page) {
        return items(page).stream().map(i -> i.path("key").asString(null)).toList();
    }

    /** The item of one issue on a page. Fails when it is not there. */
    public static JsonNode item(ApiResponse page, String key) {
        return items(page).stream().filter(i -> key.equals(i.path("key").asString(null))).findFirst()
                .orElseThrow(() -> new AssertionError(page.request() + ": no item " + key + " in " + page.snippet()));
    }
}
