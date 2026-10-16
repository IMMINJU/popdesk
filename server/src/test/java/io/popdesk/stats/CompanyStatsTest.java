package io.popdesk.stats;

import static io.popdesk.stats.StatsSteps.counts;
import static io.popdesk.stats.StatsSteps.itemOf;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.trash.TrashSteps;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The statistics of slice 7b (STATS-1, STATS-2): per customer company the number of issues in each status, without
 * the issues in the trash, in the order of the most open issues. Each test makes its own companies and compares
 * their items only: the database holds the companies of other tests too.
 */
class CompanyStatsTest extends BareAppTest {

    /** Files an issue of {@code customer} in the company and lets the admin take it to {@code status}. */
    private Issue issueIn(IssueFlow flow, Session admin, Session customer, String code, String status) {
        return flow.fileIn(customer, NewIssue.in(code), admin, status);
    }

    @Test
    @DisplayName("S7B-AC-01 each company has its code, name, status, and five counts (total, open as RECEIVED and "
            + "CONFIRMED, in progress, resolved, closed); a company without issues has zeros, suspended and archived "
            + "companies are listed with their status")
    void eachCompanyHasItsCounts() {
        Session admin = fixtures.signInAdmin();
        IssueFlow flow = new IssueFlow(fixtures, clock);
        Company busy = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, busy.code());
        // Open 3, in progress 1, resolved 2, closed 4: counting one status as another changes a number.
        for (String status : List.of("RECEIVED", "RECEIVED", "CONFIRMED", "IN_PROGRESS", "RESOLVED", "RESOLVED",
                "CLOSED", "CLOSED", "CLOSED", "CLOSED")) {
            issueIn(flow, admin, customer, busy.code(), status);
        }
        Company empty = fixtures.createCompany(admin);
        Company suspended = fixtures.createCompany(admin);
        flow.companyStatus(admin, suspended.code(), "SUSPENDED");
        Company archived = fixtures.createCompany(admin);
        fixtures.fileIssue(fixtures.customer(admin, archived.code()), archived.code());
        flow.companyStatus(admin, archived.code(), "ARCHIVED");

        List<JsonNode> items = StatsSteps.items(admin);

        SoftAssertions softly = new SoftAssertions();
        JsonNode ofBusy = find(items, busy.code());
        Set<String> fields = new TreeSet<>();
        ofBusy.propertyNames().forEach(fields::add);
        softly.assertThat(fields).as("fields of %s", ofBusy).isEqualTo(new TreeSet<>(StatsSteps.FIELDS));
        softly.assertThat(ofBusy.path("companyName").asString(null)).as("companyName").isEqualTo(busy.name());
        expect(softly, find(items, empty.code()), "ACTIVE", 0, 0, 0, 0, 0);
        expect(softly, ofBusy, "ACTIVE", 10, 3, 1, 2, 4);
        expect(softly, find(items, suspended.code()), "SUSPENDED", 0, 0, 0, 0, 0);
        expect(softly, find(items, archived.code()), "ARCHIVED", 1, 1, 0, 0, 0);
        softly.assertAll();
    }

    @Test
    @DisplayName("S7B-AC-02 issues in the trash and purged issues are not counted, private issues are; the agent "
            + "of the company gets the same counts")
    void trashIsNotCountedPrivateIs() {
        Session admin = fixtures.signInAdmin();
        IssueFlow flow = new IssueFlow(fixtures, clock);
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        Session agent = fixtures.agent(admin, company.code());
        fixtures.fileIssue(customer, company.code());
        Issue secret = fixtures.fileIssue(customer, NewIssue.in(company.code()).privateIssue());
        flow.moveTo(admin, secret.key(), "IN_PROGRESS");
        Issue trashed = fixtures.fileIssue(customer, company.code());
        Issue purged = issueIn(flow, admin, customer, company.code(), "RESOLVED");
        Issue closedAndTrashed = issueIn(flow, admin, customer, company.code(), "CLOSED");
        TrashSteps.delete(admin, trashed.key());
        TrashSteps.delete(admin, closedAndTrashed.key());
        TrashSteps.delete(admin, purged.key());
        TrashSteps.purge(admin, purged.key());

        JsonNode ofAdmin = itemOf(admin, company.code());
        JsonNode ofAgent = itemOf(agent, company.code());

        assertThat(counts(ofAdmin)).as("total, open, inProgress, resolved, closed for the admin: %s", ofAdmin)
                .containsExactly(2L, 1L, 1L, 0L, 0L);
        assertThat(counts(ofAgent)).as("counts for the agent: %s", ofAgent).isEqualTo(counts(ofAdmin));
    }

    @Test
    @DisplayName("S7B-AC-04 the items come in the order of the most open issues, then by company code, all in one "
            + "answer without pages")
    void mostOpenFirstThenByCode() {
        Session admin = fixtures.signInAdmin();
        IssueFlow flow = new IssueFlow(fixtures, clock);
        String base = Unique.companyCode();
        // Open counts 2, 1, 1, 1, 0. Among the three with one open issue (B, C, D) the code order is neither the
        // order of their totals (2, 1, 3), nor the order they were made in (C, D, B), nor the order of their names.
        Company twoOpen = company(admin, base + "E", "4");
        Company oneOpenAlone = company(admin, base + "C", "1");
        Company oneOpenMost = company(admin, base + "D", "3");
        Company oneOpenMore = company(admin, base + "B", "2");
        Company noneOpen = company(admin, base + "A", "5");
        Session inE = fixtures.customer(admin, twoOpen.code());
        issueIn(flow, admin, inE, twoOpen.code(), "RECEIVED");
        issueIn(flow, admin, inE, twoOpen.code(), "CONFIRMED");
        issueIn(flow, admin, fixtures.customer(admin, oneOpenAlone.code()), oneOpenAlone.code(), "CONFIRMED");
        Session inD = fixtures.customer(admin, oneOpenMost.code());
        issueIn(flow, admin, inD, oneOpenMost.code(), "RECEIVED");
        issueIn(flow, admin, inD, oneOpenMost.code(), "CLOSED");
        issueIn(flow, admin, inD, oneOpenMost.code(), "CLOSED");
        Session inB = fixtures.customer(admin, oneOpenMore.code());
        issueIn(flow, admin, inB, oneOpenMore.code(), "RECEIVED");
        issueIn(flow, admin, inB, oneOpenMore.code(), "RESOLVED");
        issueIn(flow, admin, fixtures.customer(admin, noneOpen.code()), noneOpen.code(), "IN_PROGRESS");
        List<String> mine = List.of(twoOpen.code(), oneOpenMore.code(), oneOpenAlone.code(), oneOpenMost.code(),
                noneOpen.code());

        JsonNode answer = StatsSteps.read(admin).json();
        List<JsonNode> items = answer.path("items").values().stream().toList();
        long companies = admin.client().get("/api/console/companies").query("size", 1).expect(200).json()
                .path("totalElements").asLong(-1);

        Set<String> fields = new TreeSet<>();
        answer.propertyNames().forEach(fields::add);
        assertThat(fields).as("fields of the answer: one list, no page fields").containsExactly("items");
        assertThat((long) items.size()).as("items against totalElements of the admin's company list")
                .isEqualTo(companies);
        assertThat(StatsSteps.codes(items).stream().filter(mine::contains).toList())
                .as("the order of this test's companies").isEqualTo(mine);
        List<String> outOfOrder = new ArrayList<>();
        for (int i = 1; i < items.size(); i++) {
            JsonNode before = items.get(i - 1);
            JsonNode after = items.get(i);
            long openBefore = StatsSteps.count(before, "open");
            long openAfter = StatsSteps.count(after, "open");
            if (openBefore < openAfter
                    || openBefore == openAfter && StatsSteps.code(before).compareTo(StatsSteps.code(after)) >= 0) {
                outOfOrder.add(StatsSteps.code(before) + " (" + openBefore + ") before " + StatsSteps.code(after)
                        + " (" + openAfter + ")");
            }
        }
        assertThat(outOfOrder).as("neighbouring items out of order in the whole list").isEmpty();
    }

    /** A company whose name sorts by {@code nameKey}, not by its code. */
    private Company company(Session admin, String code, String nameKey) {
        String name = "Company " + nameKey + " " + code;
        return fixtures.createCompany(admin, code, name, name + " Ltd.");
    }

    private static JsonNode find(List<JsonNode> items, String code) {
        return items.stream().filter(n -> code.equals(StatsSteps.code(n))).findFirst()
                .orElseThrow(() -> new AssertionError("No statistics item for company " + code));
    }

    private static void expect(SoftAssertions softly, JsonNode item, String status, long total, long open,
            long inProgress, long resolved, long closed) {
        softly.assertThat(item.path("companyStatus").asString(null)).as("companyStatus of %s", item)
                .isEqualTo(status);
        softly.assertThat(counts(item)).as("total, open, inProgress, resolved, closed of %s", item)
                .containsExactly(total, open, inProgress, resolved, closed);
    }
}
