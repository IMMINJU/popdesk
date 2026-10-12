package io.popdesk.issue;

import static io.popdesk.issue.IssueFlow.list;
import static io.popdesk.issue.IssueFlow.listRequest;
import static io.popdesk.issue.IssueFlow.total;
import static io.popdesk.issue.IssueSteps.fieldCode;
import static io.popdesk.issue.IssueSteps.items;
import static io.popdesk.issue.IssueSteps.keys;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The filters of the portal issue list (status, company, mine), the staff-only names it refuses, and
 * that no filter or search word widens what a customer sees (RULE-02): other companies' issues and a
 * colleague's private issues stay out of {@code items} and out of {@code totalElements}.
 */
class PortalIssueFilterTest extends BareAppTest {

    /** Stands for the code of the other company in {@link #extraFilters()}. */
    private static final String OTHER_CODE = "@other";

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    private static void assertEmptyPage(ApiResponse page, String what) {
        assertThat(items(page)).as("items of %s", what).isEmpty();
        assertThat(total(page)).as("totalElements of %s", what).isZero();
    }

    // S3-AC-08

    @Test
    @DisplayName("S3-AC-08 mine=true gives only what the caller reported; mine=false is the list without it")
    void mineFilter() {
        World w = flow.world();
        Issue open = fixtures.fileIssue(w.reporter(), w.code());
        Issue hidden = fixtures.fileIssue(w.reporter(), NewIssue.in(w.code()).privateIssue());
        Issue ofColleague = fixtures.fileIssue(w.colleague(), w.code());

        ApiResponse mine = list(w.reporter(), "mine", "true");
        ApiResponse notOnlyMine = list(w.reporter(), "mine", "false");
        ApiResponse plain = list(w.reporter());

        assertThat(keys(mine)).as("mine=true").containsExactlyInAnyOrder(open.key(), hidden.key());
        assertThat(total(mine)).as("totalElements of mine=true").isEqualTo(2);
        assertThat(keys(notOnlyMine)).as("mine=false")
                .containsExactlyInAnyOrder(open.key(), hidden.key(), ofColleague.key())
                .isEqualTo(keys(plain));
        assertThat(total(notOnlyMine)).as("totalElements of mine=false").isEqualTo(total(plain));
    }

    @Test
    @DisplayName("S3-AC-08 statuses, a company code, and mine together give only issues that match all of them")
    void filtersCombine() {
        World w = flow.world();
        Company other = fixtures.createCompany(w.admin());
        Session ofBoth = fixtures.customer(w.admin(), w.code(), other.code());
        Session agentOfBoth = fixtures.agent(w.admin(), w.code(), other.code());
        Issue confirmed = flow.fileIn(ofBoth, NewIssue.in(w.code()), agentOfBoth, "CONFIRMED");
        Issue resolved = flow.fileIn(ofBoth, NewIssue.in(w.code()), agentOfBoth, "RESOLVED");
        fixtures.fileIssue(ofBoth, w.code());
        Issue ofColleague = flow.fileIn(w.colleague(), NewIssue.in(w.code()), agentOfBoth, "CONFIRMED");
        flow.fileIn(ofBoth, NewIssue.in(other.code()), agentOfBoth, "CONFIRMED");

        ApiResponse mine = list(ofBoth, "status", "CONFIRMED", "status", "RESOLVED", "companyCode", w.code(),
                "mine", "true");
        ApiResponse everyones = list(ofBoth, "status", "CONFIRMED", "status", "RESOLVED", "companyCode", w.code());

        assertThat(keys(mine)).as("my CONFIRMED or RESOLVED issues in %s", w.code())
                .containsExactlyInAnyOrder(confirmed.key(), resolved.key());
        assertThat(total(mine)).as("totalElements with mine").isEqualTo(2);
        assertThat(keys(everyones)).as("CONFIRMED or RESOLVED issues in %s", w.code())
                .containsExactlyInAnyOrder(confirmed.key(), resolved.key(), ofColleague.key());
        assertThat(total(everyones)).as("totalElements without mine").isEqualTo(3);
    }

    @Test
    @DisplayName("S3-AC-08 the code of a company the customer is not a member of gives an empty page, the same body as a code that does not exist")
    void foreignCompanyCodeIsEmptyLikeMissingCode() {
        World w = flow.world();
        Company other = fixtures.createCompany(w.admin());
        Session carol = fixtures.customer(w.admin(), other.code());
        fixtures.fileIssue(w.reporter(), w.code());
        fixtures.fileIssue(carol, other.code());

        ApiResponse foreign = list(w.reporter(), "companyCode", other.code());
        ApiResponse missing = list(w.reporter(), "companyCode", Unique.companyCode());

        assertEmptyPage(foreign, "the list for a company of others");
        assertEmptyPage(missing, "the list for a missing code");
        assertThat(foreign.json()).as("body for a company of others vs a missing code").isEqualTo(missing.json());
    }

    // S3-AC-09

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
            "reporter, 1",
            "label, bug",
            "sort, 'createdAt,asc'",
            "company, AB",
    })
    @DisplayName("S3-AC-09 staff-only names and the old company name on the customer list are 400 UNKNOWN_PARAMETER")
    void staffOnlyNamesAreRejected(String name, String value) {
        World w = flow.world();

        ApiResponse rejected = listRequest(w.reporter(), name, value, "status", "RECEIVED").send();

        assertThat(rejected.status()).as(rejected.request()).isEqualTo(400);
        assertThat(rejected.errorCode()).as(rejected.request()).isEqualTo("INVALID_INPUT");
        assertThat(fieldCode(rejected, name)).as(rejected.request()).isEqualTo("UNKNOWN_PARAMETER");
    }

    // S3-AC-10

    static Stream<List<String>> extraFilters() {
        return Stream.of(
                List.of(),
                List.of("status", "RECEIVED"),
                List.of("companyCode", OTHER_CODE),
                List.of("mine", "false"),
                List.of("status", "RECEIVED", "companyCode", OTHER_CODE, "mine", "false"));
    }

    private static Object[] query(String word, List<String> extra, String otherCode) {
        List<Object> query = new ArrayList<>(List.of("q", word));
        extra.forEach(part -> query.add(OTHER_CODE.equals(part) ? otherCode : part));
        return query.toArray();
    }

    @ParameterizedTest(name = "q with {0}")
    @MethodSource("extraFilters")
    @DisplayName("S3-AC-10 a customer's search finds nothing, and counts nothing, of another company's issue or a colleague's private issue, whatever filters go with it")
    void searchDoesNotWidenTheCustomerScope(List<String> extra) {
        World w = flow.world();
        Company other = fixtures.createCompany(w.admin());
        Session carol = fixtures.customer(w.admin(), other.code());
        String shared = IssueFlow.word();
        String otherTitle = IssueFlow.word();
        String otherContent = IssueFlow.word();
        String privateTitle = IssueFlow.word();
        String privateContent = IssueFlow.word();
        Issue own = fixtures.fileIssue(w.reporter(), NewIssue.in(w.code()).title("Invoice " + shared));
        fixtures.fileIssue(w.colleague(), NewIssue.in(w.code()).privateIssue()
                .title("Salary " + privateTitle + " " + shared).content("Payroll " + privateContent));
        fixtures.fileIssue(carol, NewIssue.in(other.code())
                .title("Merger " + otherTitle + " " + shared).content("Plan " + otherContent));
        boolean narrowedToOther = extra.contains(OTHER_CODE);

        SoftAssertions softly = new SoftAssertions();
        for (String hiddenWord : List.of(otherTitle, otherContent, privateTitle, privateContent)) {
            ApiResponse page = list(w.reporter(), query(hiddenWord, extra, other.code()));
            softly.assertThat(keys(page)).as("items for a word of an issue the customer cannot see, %s", extra)
                    .isEmpty();
            softly.assertThat(total(page)).as("totalElements for a word of an issue the customer cannot see, %s",
                    extra).isZero();
        }
        ApiResponse bySharedWord = list(w.reporter(), query(shared, extra, other.code()));
        softly.assertThat(keys(bySharedWord)).as("items for a word that the customer's own issue also holds, %s", extra)
                .isEqualTo(narrowedToOther ? List.of() : List.of(own.key()));
        softly.assertThat(total(bySharedWord)).as("totalElements for the shared word, %s", extra)
                .isEqualTo(narrowedToOther ? 0 : 1);
        softly.assertAll();
    }

    @Test
    @DisplayName("S3-AC-10 a customer's status filter gives only issues they could see without it, in items and in totalElements")
    void statusFilterDoesNotWidenTheCustomerScope() {
        World w = flow.world();
        Company other = fixtures.createCompany(w.admin());
        Session carol = fixtures.customer(w.admin(), other.code());
        Issue own = fixtures.fileIssue(w.reporter(), w.code());
        Issue ofColleague = flow.fileIn(w.colleague(), NewIssue.in(w.code()), w.agent(), "CONFIRMED");
        Issue colleaguePrivate = fixtures.fileIssue(w.colleague(), NewIssue.in(w.code()).privateIssue());
        Issue ofOther = fixtures.fileIssue(carol, other.code());

        ApiResponse received = list(w.reporter(), "status", "RECEIVED");
        ApiResponse both = list(w.reporter(), "status", "RECEIVED", "status", "CONFIRMED", "mine", "false");

        assertThat(keys(received)).as("RECEIVED issues the customer sees").containsExactly(own.key())
                .doesNotContain(colleaguePrivate.key(), ofOther.key());
        assertThat(total(received)).as("totalElements of RECEIVED").isEqualTo(1);
        assertThat(keys(both)).as("RECEIVED or CONFIRMED issues the customer sees")
                .containsExactlyInAnyOrder(own.key(), ofColleague.key());
        assertThat(total(both)).as("totalElements of RECEIVED or CONFIRMED").isEqualTo(2);
    }
}
