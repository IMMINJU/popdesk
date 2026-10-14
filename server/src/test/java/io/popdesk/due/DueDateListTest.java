package io.popdesk.due;

import static io.popdesk.due.DueSteps.UTC;
import static io.popdesk.due.DueSteps.assertRefused;
import static io.popdesk.due.DueSteps.fieldCode;
import static io.popdesk.due.DueSteps.items;
import static io.popdesk.due.DueSteps.keys;
import static io.popdesk.due.DueSteps.set;
import static io.popdesk.issue.IssueFlow.list;
import static io.popdesk.issue.IssueFlow.listRequest;
import static io.popdesk.issue.IssueFlow.total;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.due.DueSteps.Scene;
import io.popdesk.issue.IssueFlow;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code sort=dueDate} on the console issue list, and the pages it gives. Each test lists one company
 * it made ({@code companyCode}), so issues of other tests do not count.
 */
class DueDateListTest extends BareAppTest {

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

    private Issue file(Scene s, String title) {
        return fixtures.fileIssue(s.customer(), NewIssue.in(s.code()).title(title));
    }

    // S6A-AC-53

    @Test
    @DisplayName("S6A-AC-53 sort=dueDate lists the earliest due date first and issues without one last; the same "
            + "date, and no date, newest first and then by number; without sort, or with sort=createdAt, the order "
            + "stays newest first")
    void dueDateOrder() {
        Scene s = due.scene();
        Issue a = file(s, "Due " + IssueFlow.word());
        flow.tick();
        Issue b = due.file(s);
        flow.tick();
        Issue c = due.file(s);
        Issue d = due.file(s);
        flow.tick();
        Issue e = due.file(s);
        flow.tick();
        Issue f = due.file(s);
        set(s.agent(), a.key(), today().plusDays(3));
        set(s.agent(), c.key(), today().plusDays(1));
        set(s.agent(), d.key(), today().plusDays(1));
        set(s.agent(), e.key(), today().plusDays(1));

        ApiResponse byDueDate = list(s.agent(), "companyCode", s.code(), "sort", "dueDate");
        ApiResponse byDefault = list(s.agent(), "companyCode", s.code());
        ApiResponse byCreatedAt = list(s.agent(), "companyCode", s.code(), "sort", "createdAt");

        assertThat(keys(byDueDate)).as("sort=dueDate").containsExactly(e.key(), d.key(), c.key(), a.key(), f.key(),
                b.key());
        assertThat(keys(byDefault)).as("no sort").containsExactly(f.key(), e.key(), d.key(), c.key(), b.key(),
                a.key());
        assertThat(keys(byCreatedAt)).as("sort=createdAt").isEqualTo(keys(byDefault));
        assertThat(total(byDueDate)).as("totalElements with sort=dueDate").isEqualTo(total(byDefault)).isEqualTo(6);
    }

    @Test
    @DisplayName("S6A-AC-53 sort=dueDate goes together with q: only the issues q finds, in due date order")
    void dueDateOrderWithSearch() {
        Scene s = due.scene();
        String word = IssueFlow.word();
        Issue a = file(s, "Due " + word + " one");
        flow.tick();
        due.file(s);
        flow.tick();
        Issue c = file(s, "Due " + word + " two");
        flow.tick();
        Issue f = file(s, "Due " + word + " three");
        set(s.agent(), a.key(), today().plusDays(5));
        set(s.agent(), c.key(), today().plusDays(2));

        ApiResponse found = list(s.agent(), "companyCode", s.code(), "q", word, "sort", "dueDate");

        assertThat(keys(found)).as("q with sort=dueDate").containsExactly(c.key(), a.key(), f.key());
        assertThat(total(found)).as("totalElements").isEqualTo(3);
        assertThat(keys(list(s.agent(), "companyCode", s.code(), "q", word))).as("q without sort")
                .containsExactly(f.key(), c.key(), a.key());
    }

    @ParameterizedTest(name = "sort={0}")
    @ValueSource(strings = {"duedate", "dueDate,asc", "createdAt,asc", "priority"})
    @DisplayName("S6A-AC-53 any other value of sort is 400 INVALID_INPUT with sort UNKNOWN_VALUE")
    void unknownSort(String value) {
        ApiResponse rejected = listRequest(flow.admin(), "sort", value, "page", 1).send();

        assertRefused(rejected, 400, "INVALID_INPUT");
        assertThat(fieldCode(rejected, "sort")).as("code of sort in %s", rejected.snippet()).isEqualTo("UNKNOWN_VALUE");
    }

    // S6A-AC-54

    @Test
    @DisplayName("S6A-AC-54 with sort=dueDate, issues of the same due date and the same filing time are neither "
            + "repeated nor missed across pages, and totalElements and totalPages are those of the whole list, "
            + "with and without a status filter")
    void pagesInDueDateOrder() {
        Scene s = due.scene();
        List<Issue> sameDay = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            sameDay.add(due.file(s));
        }
        Issue earlier = due.file(s);
        Issue noDate1 = due.file(s);
        Issue noDate2 = due.file(s);
        for (Issue issue : sameDay) {
            set(s.agent(), issue.key(), today().plusDays(2));
        }
        set(s.agent(), earlier.key(), today().plusDays(1));
        flow.changeStatus(s.agent(), noDate1.key(), "IN_PROGRESS");

        List<String> whole = keys(list(s.agent(), "companyCode", s.code(), "sort", "dueDate"));
        List<String> paged = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            ApiResponse response = listRequest(s.agent(), "companyCode", s.code(), "sort", "dueDate", "page", page,
                    "size", 3).expect(200);
            assertThat(total(response)).as("totalElements of page %d", page).isEqualTo(8);
            assertThat(response.jsonLong("totalPages")).as("totalPages of page %d", page).isEqualTo(3);
            paged.addAll(keys(response));
        }
        ApiResponse past = listRequest(s.agent(), "companyCode", s.code(), "sort", "dueDate", "page", 4, "size", 3)
                .expect(200);

        assertThat(whole).as("the whole list").hasSize(8);
        assertThat(whole.getFirst()).as("first of the whole list").isEqualTo(earlier.key());
        assertThat(whole.subList(1, 6)).as("the five of the same date and time, by number, newest first")
                .isEqualTo(sameDay.reversed().stream().map(Issue::key).toList());
        assertThat(whole.subList(6, 8)).as("the two without a date, newest first").containsExactly(noDate2.key(),
                noDate1.key());
        assertThat(paged).as("pages 1 to 3 one after another").isEqualTo(whole);
        assertThat(items(past)).as("page 4").isEmpty();

        ApiResponse received = listRequest(s.agent(), "companyCode", s.code(), "status", "RECEIVED", "sort",
                "dueDate", "page", 1, "size", 3).expect(200);
        ApiResponse receivedByDefault = listRequest(s.agent(), "companyCode", s.code(), "status", "RECEIVED", "page",
                1, "size", 3).expect(200);
        assertThat(total(received)).as("totalElements of RECEIVED with sort=dueDate").isEqualTo(7)
                .isEqualTo(total(receivedByDefault));
        assertThat(received.jsonLong("totalPages")).as("totalPages of RECEIVED with sort=dueDate").isEqualTo(3);
        assertThat(keys(received)).as("first page of RECEIVED with sort=dueDate")
                .startsWith(earlier.key()).doesNotContain(noDate1.key());
    }
}
