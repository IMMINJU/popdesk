package io.popdesk.issue;

import static io.popdesk.issue.IssueFlow.NO_ACCOUNT;
import static io.popdesk.issue.IssueFlow.STATUSES;
import static io.popdesk.issue.IssueFlow.assertEmptyPage;
import static io.popdesk.issue.IssueFlow.list;
import static io.popdesk.issue.IssueFlow.listRequest;
import static io.popdesk.issue.IssueFlow.total;
import static io.popdesk.issue.IssueSteps.fieldCode;
import static io.popdesk.issue.IssueSteps.fieldNames;
import static io.popdesk.issue.IssueSteps.items;
import static io.popdesk.issue.IssueSteps.keys;
import static io.popdesk.issue.IssueSteps.texts;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The filters of the console issue list: status, company, assignee, reporter, and how they hold the
 * agent's scope, the order, and the pages. The context is shared, so a test counts only the issues
 * of the company it made: it lists as an agent assigned to that company alone, or gives its code.
 */
class IssueFilterTest extends BareAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    private Issue file(Session customer, String code) {
        Issue issue = fixtures.fileIssue(customer, code);
        flow.tick();
        return issue;
    }

    // S3-AC-01

    static Stream<List<String>> statusSets() {
        return Stream.of(List.of("RESOLVED"), List.of("CONFIRMED", "CLOSED"), STATUSES);
    }

    @ParameterizedTest(name = "status={0}")
    @MethodSource("statusSets")
    @DisplayName("S3-AC-01 the status filter, given once or several times, gives only issues with one of those statuses")
    void statusFilter(List<String> wanted) {
        World w = flow.world();
        Map<String, String> keyByStatus = new LinkedHashMap<>();
        for (String status : STATUSES) {
            keyByStatus.put(status, flow.fileIn(w.reporter(), NewIssue.in(w.code()), w.agent(), status).key());
            flow.tick();
        }
        Object[] query = wanted.stream().flatMap(status -> Stream.of("status", status)).toArray();

        ApiResponse page = list(w.agent(), query);

        assertThat(keys(page)).as("issues with status in %s", wanted)
                .containsExactlyInAnyOrderElementsOf(wanted.stream().map(keyByStatus::get).toList());
        assertThat(texts(items(page), "status")).as("statuses in the list").isSubsetOf(wanted);
        assertThat(total(page)).as("totalElements").isEqualTo(wanted.size());
    }

    @Test
    @DisplayName("S3-AC-01 a status that is none of the five is 400 with field status, also next to a known one")
    void unknownStatus() {
        Session admin = flow.admin();

        ApiResponse alone = listRequest(admin, "status", "NOSUCHSTATUS").send();
        ApiResponse withKnown = listRequest(admin, "status", "RECEIVED", "status", "NOSUCHSTATUS").send();

        for (ApiResponse rejected : List.of(alone, withKnown)) {
            assertThat(rejected.status()).as(rejected.request()).isEqualTo(400);
            assertThat(rejected.errorCode()).as(rejected.request()).isEqualTo("INVALID_INPUT");
            assertThat(fieldCode(rejected, "status")).as(rejected.request()).isEqualTo("UNKNOWN_VALUE");
        }
    }

    // S3-AC-02

    @Test
    @DisplayName("S3-AC-02 the company filter gives only that company's issues, to an admin and to an agent assigned to several")
    void companyCodeFilter() {
        World w = flow.world();
        Company other = fixtures.createCompany(w.admin());
        Session carol = fixtures.customer(w.admin(), other.code());
        Session agentOfBoth = fixtures.agent(w.admin(), w.code(), other.code());
        Issue first = file(w.reporter(), w.code());
        Issue second = file(w.colleague(), w.code());
        Issue ofOther = file(carol, other.code());

        for (Session staff : List.of(w.admin(), agentOfBoth)) {
            ApiResponse page = list(staff, "companyCode", w.code());
            ApiResponse otherPage = list(staff, "companyCode", other.code());
            assertThat(keys(page)).as("issues of %s for %s", w.code(), staff.role())
                    .containsExactlyInAnyOrder(first.key(), second.key());
            assertThat(total(page)).as("totalElements for %s", staff.role()).isEqualTo(2);
            assertThat(keys(otherPage)).as("issues of %s for %s", other.code(), staff.role())
                    .containsExactly(ofOther.key());
        }
    }

    @Test
    @DisplayName("S3-AC-02 a company the agent is not assigned to gives 200 with an empty page, the same body as a code that does not exist")
    void unseenCompanyIsEmptyLikeMissingCode() {
        World w = flow.world();
        Company other = fixtures.createCompany(w.admin());
        Session carol = fixtures.customer(w.admin(), other.code());
        file(w.reporter(), w.code());
        file(carol, other.code());
        String missingCode = Unique.companyCode();

        ApiResponse adminMissing = list(w.admin(), "companyCode", missingCode);
        ApiResponse agentUnassigned = list(w.agent(), "companyCode", other.code());
        ApiResponse agentMissing = list(w.agent(), "companyCode", missingCode);
        ApiResponse agentOfNothing = list(w.unassignedAgent(), "companyCode", w.code());

        assertEmptyPage(adminMissing, "an admin's list for a missing code");
        assertEmptyPage(agentUnassigned, "an agent's list for an unassigned company");
        assertEmptyPage(agentMissing, "an agent's list for a missing code");
        assertEmptyPage(agentOfNothing, "the list of an agent assigned to nothing");
        assertThat(agentUnassigned.json()).as("body for an unassigned company vs a missing code")
                .isEqualTo(agentMissing.json())
                .isEqualTo(adminMissing.json());
    }

    // S3-AC-03

    @Test
    @DisplayName("S3-AC-03 the assignee filter takes an account ID, me (the caller), and none")
    void assigneeFilter() {
        World w = flow.world();
        Session second = fixtures.agent(w.admin(), w.code());
        Issue ofFirst = file(w.reporter(), w.code());
        Issue ofSecond = file(w.reporter(), w.code());
        Issue free = file(w.reporter(), w.code());
        flow.assign(w.admin(), ofFirst.key(), w.agent().accountId());
        flow.assign(w.admin(), ofSecond.key(), second.accountId());

        ApiResponse byId = list(w.agent(), "assignee", second.accountId());
        ApiResponse mineOfFirst = list(w.agent(), "assignee", "me");
        ApiResponse mineOfSecond = list(second, "assignee", "me");
        ApiResponse none = list(w.agent(), "assignee", "none");

        assertThat(keys(byId)).as("issues assigned to the second agent").containsExactly(ofSecond.key());
        assertThat(keys(mineOfFirst)).as("assignee=me for the first agent").containsExactly(ofFirst.key());
        assertThat(keys(mineOfSecond)).as("assignee=me for the second agent").containsExactly(ofSecond.key());
        assertThat(keys(none)).as("issues without an assignee").containsExactly(free.key());
        assertThat(List.of(total(byId), total(mineOfFirst), total(mineOfSecond), total(none)))
                .as("totalElements of the four lists").containsOnly(1L);
    }

    @ParameterizedTest(name = "assignee={0}")
    @ValueSource(strings = {"abc", "-1"})
    @DisplayName("S3-AC-03 an assignee that is neither an account ID nor one of the words is 400 with field assignee")
    void meaninglessAssignee(String value) {
        Session admin = flow.admin();

        ApiResponse rejected = listRequest(admin, "assignee", value).send();

        assertThat(rejected.status()).as(rejected.request()).isEqualTo(400);
        assertThat(rejected.errorCode()).isEqualTo("INVALID_INPUT");
        assertThat(fieldNames(rejected)).as("fields of %s", rejected.request()).contains("assignee");
    }

    // S3-AC-04

    @Test
    @DisplayName("S3-AC-04 assignee=unavailable gives the issues whose assignee was taken off the company (and is still assigned to another) or suspended, agent or admin, and no others")
    void unavailableAssignee() {
        World w = flow.world();
        Session unassigned = fixtures.agent(w.admin(), w.code(), w.elsewhere().code());
        Session suspended = fixtures.agent(w.admin(), w.code());
        Session otherAdmin = fixtures.admin(w.admin());
        Session suspendedAdmin = fixtures.admin(w.admin());
        Issue ofUnassigned = file(w.reporter(), w.code());
        Issue ofSuspended = file(w.reporter(), w.code());
        Issue ofWorkingAgent = file(w.reporter(), w.code());
        Issue ofAdmin = file(w.reporter(), w.code());
        Issue ofSuspendedAdmin = file(w.reporter(), w.code());
        Issue free = file(w.reporter(), w.code());
        flow.assign(w.admin(), ofUnassigned.key(), unassigned.accountId());
        flow.assign(w.admin(), ofSuspended.key(), suspended.accountId());
        flow.assign(w.admin(), ofWorkingAgent.key(), w.agent().accountId());
        flow.assign(w.admin(), ofAdmin.key(), otherAdmin.accountId());
        flow.assign(w.admin(), ofSuspendedAdmin.key(), suspendedAdmin.accountId());
        ApiResponse whileAllCanSee = list(w.admin(), "assignee", "unavailable", "companyCode", w.code());
        flow.unassignAgent(w.admin(), w.code(), unassigned.accountId());
        flow.suspend(w.admin(), suspended.accountId());
        flow.suspend(w.admin(), suspendedAdmin.accountId());

        ApiResponse page = list(w.admin(), "assignee", "unavailable", "companyCode", w.code());

        assertEmptyPage(whileAllCanSee, "assignee=unavailable while every assignee can see the issue");
        assertThat(keys(page)).as("issues whose assignee cannot see them")
                .containsExactlyInAnyOrder(ofUnassigned.key(), ofSuspended.key(), ofSuspendedAdmin.key())
                .doesNotContain(ofWorkingAgent.key(), ofAdmin.key(), free.key());
        assertThat(total(page)).as("totalElements").isEqualTo(3);
    }

    // S3-AC-05

    @Test
    @DisplayName("S3-AC-05 the reporter filter gives only what that account reported, and an empty page for an account that does not exist")
    void reporterFilter() {
        World w = flow.world();
        Issue open = file(w.reporter(), w.code());
        Issue hidden = fixtures.fileIssue(w.reporter(), NewIssue.in(w.code()).privateIssue());
        file(w.colleague(), w.code());

        ApiResponse byReporter = list(w.agent(), "reporter", w.reporter().accountId());
        ApiResponse byNobody = list(w.agent(), "reporter", NO_ACCOUNT);

        assertThat(keys(byReporter)).as("issues the reporter filed")
                .containsExactlyInAnyOrder(open.key(), hidden.key());
        assertThat(total(byReporter)).as("totalElements").isEqualTo(2);
        assertEmptyPage(byNobody, "the list for an account that does not exist");
    }

    // S3-AC-06

    @Test
    @DisplayName("S3-AC-06 status, assignee, and reporter together give only issues that match all three")
    void filtersCombine() {
        World w = flow.world();
        Issue match = flow.fileIn(w.reporter(), NewIssue.in(w.code()), w.agent(), "IN_PROGRESS");
        Issue noAssignee = flow.fileIn(w.reporter(), NewIssue.in(w.code()), w.agent(), "IN_PROGRESS");
        Issue otherStatus = file(w.reporter(), w.code());
        Issue otherReporter = flow.fileIn(w.colleague(), NewIssue.in(w.code()), w.agent(), "IN_PROGRESS");
        for (Issue assigned : List.of(match, otherStatus, otherReporter)) {
            flow.assign(w.agent(), assigned.key(), w.agent().accountId());
        }

        ApiResponse page = list(w.agent(), "status", "IN_PROGRESS", "assignee", "me", "reporter",
                w.reporter().accountId(), "companyCode", w.code());

        assertThat(keys(page)).as("issues matching all filters").containsExactly(match.key())
                .doesNotContain(noAssignee.key());
        assertThat(total(page)).as("totalElements").isEqualTo(1);
    }

    @Test
    @DisplayName("S3-AC-06 an agent's status filter without a company code leaves out unassigned companies, in items and in totalElements")
    void statusFilterKeepsTheAgentScope() {
        World w = flow.world();
        Company other = fixtures.createCompany(w.admin());
        Session carol = fixtures.customer(w.admin(), other.code());
        Issue received = file(w.reporter(), w.code());
        flow.fileIn(w.reporter(), NewIssue.in(w.code()), w.agent(), "CONFIRMED");
        Issue ofOther = file(carol, other.code());

        ApiResponse page = list(w.agent(), "status", "RECEIVED");

        assertThat(keys(page)).as("RECEIVED issues the agent sees").containsExactly(received.key())
                .doesNotContain(ofOther.key());
        assertThat(total(page)).as("totalElements").isEqualTo(1);
    }

    @Test
    @DisplayName("S3-AC-06 an agent's reporter and assignee filters leave out an unassigned company: a customer of both, an admin assigned in both, and issues without or with an unavailable assignee in both give only the agent's company, in items and in totalElements")
    void reporterAndAssigneeFiltersKeepTheAgentScope() {
        World w = flow.world();
        String other = w.elsewhere().code();
        Session ofBoth = fixtures.customer(w.admin(), w.code(), other);
        Session takenOff = fixtures.agent(w.admin(), w.code(), other);
        Issue ofAdmin = file(ofBoth, w.code());
        Issue free = file(ofBoth, w.code());
        Issue ofTakenOff = file(ofBoth, w.code());
        Issue otherOfAdmin = file(ofBoth, other);
        Issue otherFree = file(ofBoth, other);
        Issue otherOfTakenOff = file(ofBoth, other);
        flow.assign(w.admin(), ofAdmin.key(), w.admin().accountId());
        flow.assign(w.admin(), otherOfAdmin.key(), w.admin().accountId());
        flow.assign(w.admin(), ofTakenOff.key(), takenOff.accountId());
        flow.assign(w.admin(), otherOfTakenOff.key(), w.agentElsewhere().accountId());
        flow.unassignAgent(w.admin(), w.code(), takenOff.accountId());
        flow.unassignAgent(w.admin(), other, w.agentElsewhere().accountId());
        List<String> ofTheOther = List.of(otherOfAdmin.key(), otherFree.key(), otherOfTakenOff.key());
        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("reporter=" + ofBoth.accountId(), List.of(ofAdmin.key(), free.key(), ofTakenOff.key()));
        expected.put("assignee=" + w.admin().accountId(), List.of(ofAdmin.key()));
        expected.put("assignee=none", List.of(free.key()));
        expected.put("assignee=unavailable", List.of(ofTakenOff.key()));

        expected.forEach((filter, own) -> {
            String[] nameAndValue = filter.split("=");

            ApiResponse page = list(w.agent(), nameAndValue[0], nameAndValue[1]);

            assertThat(keys(page)).as("issues an agent of %s alone sees with %s", w.code(), filter)
                    .containsExactlyInAnyOrderElementsOf(own).doesNotContainAnyElementsOf(ofTheOther);
            assertThat(total(page)).as("totalElements with %s", filter).isEqualTo(own.size());
        });
    }

    @Test
    @DisplayName("S3-AC-06 an agent's search does not reach an unassigned company: no item and totalElements 0 for a word only its issue holds")
    void searchKeepsTheAgentScope() {
        World w = flow.world();
        Company other = fixtures.createCompany(w.admin());
        Session carol = fixtures.customer(w.admin(), other.code());
        String shared = IssueFlow.word();
        String onlyOther = IssueFlow.word();
        Issue own = fixtures.fileIssue(w.reporter(), NewIssue.in(w.code()).title("Printer " + shared));
        fixtures.fileIssue(carol, NewIssue.in(other.code()).title("Printer " + shared).content("Tray " + onlyOther));

        ApiResponse byOtherWord = list(w.agent(), "q", onlyOther);
        ApiResponse bySharedWord = list(w.agent(), "q", shared);
        ApiResponse bySharedWordInOther = list(w.agent(), "q", shared, "companyCode", other.code());

        assertEmptyPage(byOtherWord, "the agent's search for a word of an unassigned company");
        assertThat(keys(bySharedWord)).as("the agent's search for the shared word").containsExactly(own.key());
        assertThat(total(bySharedWord)).as("totalElements for the shared word").isEqualTo(1);
        assertEmptyPage(bySharedWordInOther, "the agent's search narrowed to the unassigned company");
    }

    @Test
    @DisplayName("S3-AC-06 a filtered list is newest first and split into pages, with totals counted after the filter")
    void filteredListIsNewestFirstInPages() {
        World w = flow.world();
        Issue first = file(w.reporter(), w.code());
        file(w.colleague(), w.code());
        Issue second = file(w.reporter(), w.code());
        file(w.colleague(), w.code());
        Issue third = file(w.reporter(), w.code());
        long reporter = w.reporter().accountId();

        ApiResponse page1 = listRequest(w.agent(), "reporter", reporter, "page", 1, "size", 2).expect(200);
        ApiResponse page2 = listRequest(w.agent(), "reporter", reporter, "page", 2, "size", 2).expect(200);
        ApiResponse page3 = listRequest(w.agent(), "reporter", reporter, "page", 3, "size", 2).expect(200);

        assertThat(keys(page1)).as("page 1").containsExactly(third.key(), second.key());
        assertThat(keys(page2)).as("page 2").containsExactly(first.key());
        assertThat(items(page3)).as("page 3").isEmpty();
        assertThat(total(page1)).as("totalElements").isEqualTo(3);
        assertThat(page1.jsonLong("totalPages")).as("totalPages").isEqualTo(2);
        assertThat(page1.jsonLong("size")).as("size").isEqualTo(2);
        assertThat(page2.jsonLong("page")).as("page").isEqualTo(2);
    }

    // S3-AC-07

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
            "company, AB",
            "label, bug",
            "sort, 'createdAt,asc'",
    })
    @DisplayName("S3-AC-07 a query name the console issue list does not have is 400 UNKNOWN_PARAMETER")
    void unknownParameter(String name, String value) {
        Session admin = flow.admin();

        ApiResponse rejected = listRequest(admin, name, value, "page", 1).send();

        assertThat(rejected.status()).as(rejected.request()).isEqualTo(400);
        assertThat(rejected.errorCode()).as(rejected.request()).isEqualTo("INVALID_INPUT");
        assertThat(fieldCode(rejected, name)).as(rejected.request()).isEqualTo("UNKNOWN_PARAMETER");
    }
}
