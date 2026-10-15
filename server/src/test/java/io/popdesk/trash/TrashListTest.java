package io.popdesk.trash;

import static io.popdesk.issue.IssueFlow.NO_KEY;
import static io.popdesk.issue.IssueFlow.time;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.link.LinkSteps;
import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

/**
 * The trash list ({@code GET /api/console/trash}, S7A-AC-20) and the trash steps on an issue that is not in the trash
 * (S7A-AC-23, TRASH-2).
 *
 * <p>The app and its database are shared with other test classes, so the list is read for the test's own company,
 * and pages are read right after the test's own deletes: the clock moves a second before each, so they are the
 * latest of the whole trash.
 */
class TrashListTest extends BareAppTest {

    private IssueFlow flow;
    private TrashRows rows;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
        rows = new TrashRows(db);
    }

    private Issue file(Session reporter, Company company) {
        return fixtures.fileIssue(reporter, NewIssue.in(company.code()).title(LinkSteps.title("Trash list")));
    }

    @Test
    @DisplayName("S7A-AC-20 the trash lists the deleted issues newest delete first, each with key, title, company code and name, status, deletedAt, deletedBy, and purgeAt 30 days after the delete; a purged issue is not there; pages follow COM-4; an agent gets 403 FORBIDDEN")
    void trashListsNewestDeleteFirst() {
        Session admin = fixtures.admin(flow.admin());
        Company company = fixtures.createCompany(admin);
        Session reporter = fixtures.customer(admin, company.code());
        Session agent = fixtures.agent(admin, company.code());
        Issue purged = file(reporter, company);
        Issue first = file(reporter, company);
        Issue second = file(reporter, company);
        Issue third = file(reporter, company);
        flow.changeStatus(agent, second.key(), "CONFIRMED");
        flow.moveTo(agent, third.key(), "RESOLVED");
        Map<String, Instant> deletedAt = new LinkedHashMap<>();
        for (Issue issue : List.of(purged, first, second, third)) {
            deletedAt.put(issue.key(), flow.tick());
            TrashSteps.delete(admin, issue.key());
        }
        TrashSteps.purge(admin, purged.key());

        List<JsonNode> all = TrashSteps.entries(admin);
        List<JsonNode> ours = all.stream()
                .filter(e -> company.code().equals(e.path("companyCode").asString(null))).toList();
        assertThat(ours.stream().map(e -> e.path("key").asString(null)).toList())
                .as("the company's issues in the trash, in the order of the list")
                .containsExactly(third.key(), second.key(), first.key());
        Map<String, String> statuses = Map.of(first.key(), "RECEIVED", second.key(), "CONFIRMED", third.key(),
                "RESOLVED");
        for (JsonNode e : ours) {
            String key = e.path("key").asString(null);
            Issue issue = key.equals(first.key()) ? first : key.equals(second.key()) ? second : third;
            assertThat(e.path("title").asString(null)).as("title of " + e).isEqualTo(LinkSteps.titleOf(issue));
            assertThat(e.path("companyName").asString(null)).as("companyName of " + e).isEqualTo(company.name());
            assertThat(e.path("status").asString(null)).as("status of " + e).isEqualTo(statuses.get(key));
            assertThat(time(e, "deletedAt")).as("deletedAt of " + e).isEqualTo(deletedAt.get(key));
            assertThat(e.path("deletedBy").path("accountId").asLong(-1)).as("deletedBy of " + e)
                    .isEqualTo(admin.accountId());
            assertThat(e.path("deletedBy").path("name").asString(null)).as("name of deletedBy of " + e)
                    .isEqualTo(admin.name());
            assertThat(time(e, "purgeAt")).as("purgeAt of " + e)
                    .isEqualTo(deletedAt.get(key).plus(Duration.ofDays(TrashSteps.PURGE_AFTER_DAYS)));
        }
        List<Instant> times = new ArrayList<>(all.stream().map(e -> time(e, "deletedAt")).toList());
        assertThat(times).as("deletedAt over the whole trash").isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(all.stream().map(e -> e.path("key").asString(null))).as("keys of the whole trash")
                .doesNotContain(purged.key());

        ApiResponse page1 = TrashSteps.list(admin, 1, 2);
        ApiResponse page2 = TrashSteps.list(admin, 2, 2);
        assertThat(keys(page1)).as("page 1 of size 2").containsExactly(third.key(), second.key());
        assertThat(keys(page2).getFirst()).as("first of page 2 of size 2").isEqualTo(first.key());
        // totalElements and its items from one read: the trash-purge job may empty other tests' old entries between
        // two reads.
        ApiResponse whole = TrashSteps.list(admin, 1, 100);
        long total = whole.jsonLong("totalElements");
        assertThat(keys(whole)).as("items of page 1 of size 100 when totalElements is " + total)
                .hasSize((int) Math.min(total, 100));
        assertThat(whole.jsonLong("totalPages")).as("totalPages of size 100").isEqualTo((total + 99) / 100);
        assertThat(page1.jsonLong("totalPages")).as("totalPages of size 2, against totalElements of the same read")
                .isEqualTo((page1.jsonLong("totalElements") + 1) / 2);

        ApiResponse byAgent = TrashSteps.listRequest(agent, "page", 1).send();
        assertThat(byAgent.status()).as("agent: " + byAgent.snippet()).isEqualTo(403);
        assertThat(byAgent.errorCode()).as("code for the agent").isEqualTo("FORBIDDEN");
    }

    private static List<String> keys(ApiResponse page) {
        return page.json().path("items").values().stream().map(e -> e.path("key").asString(null)).toList();
    }

    // S7A-AC-23

    /** An issue that is not in the trash. */
    enum Target {
        LIVE,
        PURGED,
        MISSING
    }

    /** A trash step. */
    enum Step {
        RESTORE,
        PURGE
    }

    private static ApiRequest request(Step step, Session admin, String key) {
        return step == Step.RESTORE ? TrashSteps.restoreRequest(admin, key) : TrashSteps.purgeRequest(admin, key);
    }

    @ParameterizedTest(name = "{1} of a {0} issue")
    @CsvSource({"LIVE, RESTORE", "LIVE, PURGE", "PURGED, RESTORE", "PURGED, PURGE", "MISSING, RESTORE",
            "MISSING, PURGE"})
    @DisplayName("S7A-AC-23 restoring or purging a live issue, a purged issue, or a key nobody has is the 404 NOT_FOUND of a key nobody has, and the issue stays as it was")
    void notInTheTrashIsNotFound(Target target, Step step) {
        Session admin = fixtures.admin(flow.admin());
        Company company = fixtures.createCompany(admin);
        Session reporter = fixtures.customer(admin, company.code());
        Issue issue = file(reporter, company);
        String key = target == Target.MISSING ? NO_KEY : issue.key();
        if (target == Target.PURGED) {
            TrashSteps.delete(admin, key);
            TrashSteps.purge(admin, key);
        }
        JsonNode before = target == Target.LIVE ? flow.detail(admin, key) : null;

        ApiResponse answer = request(step, admin, key).send();
        ApiResponse missing = request(step, admin, NO_KEY).send();

        TrashSteps.assertLikeMissing(answer, missing);
        if (target == Target.LIVE) {
            assertThat(flow.detail(admin, key)).as("the live issue after the " + step).isEqualTo(before);
        }
        if (target == Target.PURGED) {
            assertThat(rows.issue(key).get("title")).as("title of the purged issue after the " + step).isEqualTo("");
        }
        if (target != Target.MISSING) {
            assertThat(TrashSteps.entry(admin, key)).as("trash entry of " + key + " after the " + step).isNull();
        }
    }
}
