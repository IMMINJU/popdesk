package io.popdesk.due;

import static io.popdesk.due.DueSteps.UTC;
import static io.popdesk.due.DueSteps.assertDue;
import static io.popdesk.due.DueSteps.assertRefused;
import static io.popdesk.due.DueSteps.clear;
import static io.popdesk.due.DueSteps.clearRequest;
import static io.popdesk.due.DueSteps.fieldCode;
import static io.popdesk.due.DueSteps.item;
import static io.popdesk.due.DueSteps.set;
import static io.popdesk.due.DueSteps.setRequest;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.due.DueSteps.Scene;
import io.popdesk.issue.IssueFlow;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

/**
 * Setting and clearing an issue's due date on the console ({@code PUT} and {@code DELETE
 * .../due-date}), {@code overdue}, and the {@code DUE_DATE} history entries. This setup has no
 * {@code POPDESK_TIMEZONE}, so "today" is the UTC date of the app clock.
 */
class DueDateTest extends BareAppTest {

    private DueSteps due;
    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        due = new DueSteps(fixtures, clock);
        flow = due.flow();
    }

    private LocalDate today() {
        return due.today(UTC);
    }

    /** A {@code CHANGED} entry of {@code DUE_DATE}: both values (null for none) and who. */
    private static void assertDueDateChange(JsonNode entry, LocalDate oldDate, LocalDate newDate, Session who) {
        assertThat(entry.path("kind").asString(null)).as("kind of %s", entry).isEqualTo("CHANGED");
        assertThat(entry.path("field").asString(null)).as("field of %s", entry).isEqualTo("DUE_DATE");
        assertThat(entry.path("oldValue").asString(null)).as("oldValue of %s", entry)
                .isEqualTo(oldDate == null ? null : oldDate.toString());
        assertThat(entry.path("newValue").asString(null)).as("newValue of %s", entry)
                .isEqualTo(newDate == null ? null : newDate.toString());
        assertThat(entry.path("actor").path("accountId").asLong(-1)).as("actor of %s", entry)
                .isEqualTo(who.accountId());
    }

    // S6A-AC-30

    @Test
    @DisplayName("S6A-AC-30 an assigned agent sets today and an admin a later date: 200 with the issue, the date "
            + "is kept, overdue is false, and each change raises version by 1")
    void staffSetTodayOrLater() {
        Scene s = due.scene();
        Issue issue = due.file(s);
        long before = due.version(s.agent(), issue.key());
        LocalDate later = today().plusDays(30);

        JsonNode byAgent = set(s.agent(), issue.key(), today());

        assertThat(byAgent.path("key").asString(null)).as("key of the answer").isEqualTo(issue.key());
        assertDue(byAgent, today(), false, "the agent's answer");
        assertThat(byAgent.path("version").asLong(-1)).as("version after the agent set it").isEqualTo(before + 1);
        JsonNode byAdmin = set(s.admin(), issue.key(), later);
        assertDue(byAdmin, later, false, "the admin's answer");
        assertThat(byAdmin.path("version").asLong(-1)).as("version after the admin changed it")
                .isEqualTo(before + 2);
        assertDue(flow.detail(s.agent(), issue.key()), later, false, "the issue read again");
    }

    @Test
    @DisplayName("S6A-AC-30 an agent of another company sets or clears the due date of an issue they cannot see: "
            + "404, and the issue keeps its date and version")
    void agentOfAnotherCompanyGets404() {
        Scene s = due.scene();
        Issue issue = due.file(s);
        LocalDate date = today().plusDays(3);
        set(s.agent(), issue.key(), date);
        long version = due.version(s.agent(), issue.key());

        assertRefused(setRequest(s.agentElsewhere(), issue.key(), today().plusDays(9).toString()).send(), 404,
                "NOT_FOUND");
        assertRefused(clearRequest(s.agentElsewhere(), issue.key()).send(), 404, "NOT_FOUND");

        assertDue(flow.detail(s.agent(), issue.key()), date, false, "the issue read again");
        assertThat(due.version(s.agent(), issue.key())).as("version").isEqualTo(version);
    }

    // S6A-AC-31

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "yesterday, OUT_OF_RANGE",
            "with a time, INVALID_FORMAT",
            "2026-02-30, INVALID_FORMAT",
            "empty text, REQUIRED",
            "a number, INVALID_FORMAT",
    })
    @DisplayName("S6A-AC-31 a due date before today, with a time, of a day that does not exist, empty, or a number "
            + "is 400 INVALID_INPUT with the field's code, and the issue keeps its date and version")
    void refusedDueDates(String kind, String code) {
        Object value = switch (kind) {
            case "yesterday" -> today().minusDays(1).toString();
            case "with a time" -> today().plusDays(1) + "T09:00:00";
            case "empty text" -> "";
            case "a number" -> Long.parseLong(today().plusDays(1).toString().replace("-", ""));
            default -> kind;
        };
        Scene s = due.scene();
        Issue issue = due.file(s);
        LocalDate kept = today().plusDays(5);
        set(s.agent(), issue.key(), kept);
        long version = due.version(s.agent(), issue.key());

        ApiResponse refused = setRequest(s.agent(), issue.key(), value).send();

        assertRefused(refused, 400, "INVALID_INPUT");
        assertThat(fieldCode(refused, "dueDate")).as("code of dueDate for %s in %s", value, refused.snippet())
                .isEqualTo(code);
        assertDue(flow.detail(s.agent(), issue.key()), kept, false, "the issue read again");
        assertThat(due.version(s.agent(), issue.key())).as("version").isEqualTo(version);
    }

    // S6A-AC-32

    @Test
    @DisplayName("S6A-AC-32 clearing a due date gives the issue without one and raises version by 1; clearing an "
            + "issue without a due date is 200 too, keeps the version, and leaves no history entry")
    void clearing() {
        Scene s = due.scene();
        Issue withDate = due.file(s);
        Issue without = due.file(s);
        set(s.agent(), withDate.key(), today().plusDays(2));
        long version = due.version(s.agent(), withDate.key());
        long versionWithout = due.version(s.agent(), without.key());

        JsonNode cleared = clear(s.agent(), withDate.key());
        JsonNode clearedAgain = clear(s.admin(), without.key());

        assertDue(cleared, null, false, "the answer");
        assertThat(cleared.path("version").asLong(-1)).as("version after clearing").isEqualTo(version + 1);
        assertDue(flow.detail(s.agent(), withDate.key()), null, false, "the issue read again");
        assertDue(clearedAgain, null, false, "the answer for the issue without a date");
        assertThat(due.version(s.agent(), without.key())).as("version of the issue without a date")
                .isEqualTo(versionWithout);
        assertThat(due.dueDateHistory(s.agent(), without.key())).as("DUE_DATE entries of the issue without a date")
                .isEmpty();
    }

    // S6A-AC-33

    @Test
    @DisplayName("S6A-AC-33 on a CLOSED issue setting and clearing the due date are 400 ISSUE_CLOSED, and the issue "
            + "keeps its date and version")
    void closedIssue() {
        Scene s = due.scene();
        Issue issue = due.file(s);
        LocalDate date = today().plusDays(4);
        set(s.agent(), issue.key(), date);
        flow.changeStatus(s.agent(), issue.key(), "CLOSED");
        long version = due.version(s.agent(), issue.key());

        assertRefused(setRequest(s.agent(), issue.key(), today().plusDays(8).toString()).send(), 400, "ISSUE_CLOSED");
        assertRefused(clearRequest(s.admin(), issue.key()).send(), 400, "ISSUE_CLOSED");

        assertDue(flow.detail(s.agent(), issue.key()), date, false, "the closed issue read again");
        assertThat(due.version(s.agent(), issue.key())).as("version").isEqualTo(version);
    }

    // S6A-AC-35

    @Test
    @DisplayName("S6A-AC-35 a due date set today is overdue the next day: the issue and its list item keep the "
            + "date with overdue true, and setting that date again is 400 OUT_OF_RANGE")
    void overdueTheNextDay() {
        Scene s = due.scene();
        Issue issue = due.file(s);
        LocalDate date = today();
        set(s.agent(), issue.key(), date);

        clock.advance(Duration.ofDays(1));
        Session agent = fixtures.signInAgain(s.agent());

        assertThat(today()).as("the app's date after a day").isEqualTo(date.plusDays(1));
        assertDue(flow.detail(agent, issue.key()), date, true, "the issue a day later");
        ApiResponse page = IssueFlow.list(agent, "companyCode", s.code());
        assertDue(item(page, issue.key()), date, true, "the list item a day later");
        ApiResponse again = setRequest(agent, issue.key(), date.toString()).send();
        assertRefused(again, 400, "INVALID_INPUT");
        assertThat(fieldCode(again, "dueDate")).as("code of dueDate in %s", again.snippet()).isEqualTo("OUT_OF_RANGE");
        assertDue(flow.detail(agent, issue.key()), date, true, "the issue after setting the date again");
    }

    // S6A-AC-36

    @Test
    @DisplayName("S6A-AC-36 setting the date an issue already has is 200 and keeps the version and the history")
    void sameDateAgain() {
        Scene s = due.scene();
        Issue issue = due.file(s);
        LocalDate date = today().plusDays(6);
        set(s.agent(), issue.key(), date);
        long version = due.version(s.agent(), issue.key());

        JsonNode again = set(s.admin(), issue.key(), date);

        assertDue(again, date, false, "the answer");
        assertThat(again.path("version").asLong(-1)).as("version in the answer").isEqualTo(version);
        assertThat(due.version(s.agent(), issue.key())).as("version read again").isEqualTo(version);
        assertThat(due.dueDateHistory(s.agent(), issue.key())).as("DUE_DATE entries").hasSize(1);
    }

    // S6A-AC-61

    @Test
    @DisplayName("S6A-AC-61 setting, changing, and clearing a due date leave one DUE_DATE entry each, oldest "
            + "first, with the old and the new date, none for no date")
    void history() {
        Scene s = due.scene();
        Issue issue = due.file(s);
        LocalDate first = today().plusDays(1);
        LocalDate second = today().plusDays(10);

        flow.tick();
        set(s.agent(), issue.key(), first);
        flow.tick();
        set(s.admin(), issue.key(), second);
        flow.tick();
        clear(s.agent(), issue.key());

        List<JsonNode> entries = due.dueDateHistory(s.agent(), issue.key());
        assertThat(entries).as("DUE_DATE entries").hasSize(3);
        assertDueDateChange(entries.get(0), null, first, s.agent());
        assertDueDateChange(entries.get(1), first, second, s.admin());
        assertDueDateChange(entries.get(2), second, null, s.agent());
    }

    // S6A-AC-50

    @Test
    @DisplayName("S6A-AC-50 the console issue and its list item give dueDate and overdue: the date and false for "
            + "an issue with one, null and false for an issue without")
    void issueAndListGiveTheDueDate() {
        Scene s = due.scene();
        Issue with = due.file(s);
        Issue without = due.file(s);
        LocalDate date = today().plusDays(12);
        set(s.agent(), with.key(), date);

        ApiResponse page = IssueFlow.list(s.agent(), "companyCode", s.code());

        assertDue(item(page, with.key()), date, false, "the list item with a date");
        assertDue(item(page, without.key()), null, false, "the list item without a date");
        assertThat(item(page, without.key()).has("dueDate")).as("dueDate is in the item without a date").isTrue();
        assertDue(flow.detail(s.agent(), with.key()), date, false, "the issue with a date");
        assertDue(flow.detail(s.agent(), without.key()), null, false, "the issue without a date");
    }
}
