package io.popdesk.issue;

import static io.popdesk.issue.IssueFlow.NO_ACCOUNT;
import static io.popdesk.issue.IssueSteps.fieldCode;
import static io.popdesk.issue.IssueSteps.fieldNames;
import static io.popdesk.issue.IssueSteps.items;
import static io.popdesk.issue.IssueSteps.keys;
import static io.popdesk.issue.IssueSteps.numberOf;
import static io.popdesk.issue.IssueSteps.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow.OnBehalf;
import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * {@code POST /api/console/issues}: staff file an issue for a customer of a company. The customer is
 * the reporter, the staff member is kept as {@code createdBy}, and the portal only says that the
 * support team filed it.
 */
class OnBehalfFilingTest extends BareAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    private ApiResponse send(Session staff, OnBehalf issue) {
        return flow.onBehalfRequest(staff, issue).send();
    }

    /** The titles of the newest 100 issues an admin sees. */
    private static List<String> newestTitles(Session admin) {
        return IssueSteps.texts(items(IssueFlow.list(admin)), "title");
    }

    private static void assertRateLimited(ApiResponse response) {
        assertThat(response.status()).as(response.request()).isEqualTo(429);
        assertThat(response.errorCode()).isEqualTo("RATE_LIMITED");
        assertThat(response.header("Retry-After")).as("Retry-After").isNotBlank();
    }

    // S3-AC-32

    @ParameterizedTest(name = "{0}, {1}")
    @CsvSource({"AGENT, PHONE", "ADMIN, OTHER"})
    @DisplayName("S3-AC-32 staff file for a customer: the customer is the reporter, the staff member createdBy, the channel as sent, RECEIVED, NORMAL, public, and the next number of the company")
    void staffFileForACustomer(Role role, String channel) {
        World w = flow.world();
        Session staff = role == Role.ADMIN ? w.admin() : w.agent();
        Issue onPortal = fixtures.fileIssue(w.reporter(), w.code());
        OnBehalf request = OnBehalf.of(w.code(), w.reporter().accountId()).channel(channel)
                .content("Called about the invoice.");

        Issue filed = flow.fileOnBehalf(staff, request);

        assertThat(filed.key()).as("key after %s", onPortal.key())
                .isEqualTo(w.code() + "-" + (numberOf(onPortal.key()) + 1));
        for (JsonNode view : List.of(filed.json(), flow.detail(w.admin(), filed.key()))) {
            assertThat(text(view, "key")).isEqualTo(filed.key());
            assertThat(text(view, "companyCode")).isEqualTo(w.code());
            assertThat(text(view, "status")).isEqualTo("RECEIVED");
            assertThat(text(view, "title")).isEqualTo(request.title());
            assertThat(text(view, "content")).isEqualTo("Called about the invoice.");
            assertThat(text(view, "priority")).as("priority left out").isEqualTo("NORMAL");
            assertThat(view.path("private").isBoolean()).isTrue();
            assertThat(view.path("private").booleanValue()).as("private left out").isFalse();
            assertThat(view.path("reporter").path("accountId").asLong(-1)).as("reporter")
                    .isEqualTo(w.reporter().accountId());
            assertThat(text(view.path("reporter"), "role")).isEqualTo("CUSTOMER");
            assertThat(view.path("createdBy").path("accountId").asLong(-1)).as("createdBy")
                    .isEqualTo(staff.accountId());
            assertThat(text(view.path("createdBy"), "name")).isEqualTo(staff.name());
            assertThat(text(view, "channel")).isEqualTo(channel);
        }

        Issue urgent = flow.fileOnBehalf(staff, OnBehalf.of(w.code(), w.colleague().accountId()).channel(channel)
                .priority("URGENT").privateIssue());

        assertThat(numberOf(urgent.key())).as("number after %s", filed.key()).isEqualTo(numberOf(filed.key()) + 1);
        JsonNode kept = flow.detail(w.admin(), urgent.key());
        assertThat(text(kept, "priority")).isEqualTo("URGENT");
        assertThat(kept.path("private").asBoolean(false)).as("private as sent").isTrue();
        assertThat(kept.path("reporter").path("accountId").asLong(-1)).isEqualTo(w.colleague().accountId());
        assertThat(numberOf(fixtures.fileIssue(w.reporter(), w.code()).key())).as("the portal's next number")
                .isEqualTo(numberOf(urgent.key()) + 1);
    }

    // S3-AC-33

    @Test
    @DisplayName("S3-AC-33 a customer of another company, an invited one, a suspended one, one taken out, an agent, and an ID of nobody get the same 400 with reporterId UNKNOWN_VALUE; nothing is filed and no number is used")
    void accountsThatCannotBeTheReporterGetTheSame400() {
        World w = flow.world();
        Company other = fixtures.createCompany(w.admin());
        Session ofOther = fixtures.customer(w.admin(), other.code());
        Invitation invited = fixtures.invite(w.admin(), Role.CUSTOMER, w.code());
        Session suspended = fixtures.customer(w.admin(), w.code());
        flow.suspend(w.admin(), suspended.accountId());
        Session removed = fixtures.customer(w.admin(), w.code(), other.code());
        flow.removeCustomer(w.admin(), w.code(), removed.accountId());
        Map<String, Long> accounts = new LinkedHashMap<>();
        accounts.put("a customer of another company", ofOther.accountId());
        accounts.put("an invited customer of the company", invited.accountId());
        accounts.put("a suspended customer of the company", suspended.accountId());
        accounts.put("a customer taken out of the company", removed.accountId());
        accounts.put("an agent of the company", w.agent().accountId());
        accounts.put("an ID of nobody", NO_ACCOUNT);
        String title = Unique.text("For the wrong person");

        Map<String, ApiResponse> answers = new LinkedHashMap<>();
        accounts.forEach((who, id) -> answers.put(who, send(w.agent(), OnBehalf.of(w.code(), id).title(title))));

        ApiResponse first = answers.values().iterator().next();
        answers.forEach((who, answer) -> {
            assertThat(answer.status()).as("status for %s", who).isEqualTo(400);
            assertThat(answer.errorCode()).as("code for %s", who).isEqualTo("INVALID_INPUT");
            assertThat(fieldCode(answer, "reporterId")).as("field reporterId for %s", who).isEqualTo("UNKNOWN_VALUE");
            assertThat(answer.text()).as("body for %s, like for a customer of another company", who)
                    .isEqualTo(first.text());
        });
        assertThat(newestTitles(w.admin())).as("titles of the newest issues").doesNotContain(title);
        assertThat(keys(IssueFlow.list(ofOther))).as("issues the customer of the other company sees").isEmpty();
        assertThat(fixtures.fileIssue(w.reporter(), w.code()).key()).as("the first key of the company")
                .isEqualTo(w.code() + "-1");
    }

    // S3-AC-34

    @Test
    @DisplayName("S3-AC-34 an agent who is not assigned to the company gets 404, the same body as for a code that does not exist, also with wrong input; nothing is filed")
    void unassignedAgentGetsNotFound() {
        World w = flow.world();
        String title = Unique.text("Not my company");
        flow.fileOnBehalf(w.agent(), OnBehalf.of(w.code(), w.reporter().accountId()));

        ApiResponse missing = send(w.unassignedAgent(),
                OnBehalf.of(Unique.companyCode(), w.reporter().accountId()).title(title));
        ApiResponse unassigned = send(w.unassignedAgent(), OnBehalf.of(w.code(), w.reporter().accountId()).title(title));
        ApiResponse wrongInput = send(w.unassignedAgent(), OnBehalf.of(w.code(), w.reporter().accountId())
                .title("").channel("EMAIL").priority("CRITICAL"));
        ApiResponse wrongReporter = send(w.unassignedAgent(),
                OnBehalf.of(w.code(), w.agent().accountId()).title(title));

        assertThat(missing.status()).as(missing.request()).isEqualTo(404);
        assertThat(missing.errorCode()).isEqualTo("NOT_FOUND");
        for (ApiResponse refused : List.of(unassigned, wrongInput, wrongReporter)) {
            assertThat(refused.status()).as(refused.request()).isEqualTo(404);
            assertThat(refused.body()).as("body of a refused filing, like a missing code").isEqualTo(missing.body());
        }
        assertThat(newestTitles(w.admin())).as("titles of the newest issues").doesNotContain(title);
        assertThat(fixtures.fileIssue(w.reporter(), w.code()).key()).as("the second key of the company")
                .isEqualTo(w.code() + "-2");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"SUSPENDED", "ARCHIVED"})
    @DisplayName("S3-AC-34 filing for a customer of a company that is not ACTIVE is 400 COMPANY_NOT_ACTIVE, for an admin and an assigned agent; an agent who is not assigned still gets the 404 of a code that does not exist")
    void inactiveCompanyIsRefused(String status) {
        World w = flow.world();
        flow.fileOnBehalf(w.agent(), OnBehalf.of(w.code(), w.reporter().accountId()));
        flow.companyStatus(w.admin(), w.code(), status);
        String title = Unique.text("Company is " + status);

        for (Session staff : List.of(w.admin(), w.agent())) {
            ApiResponse refused = send(staff, OnBehalf.of(w.code(), w.reporter().accountId()).title(title));

            assertThat(refused.status()).as(refused.request() + " by " + staff.role()).isEqualTo(400);
            assertThat(refused.errorCode()).isEqualTo("COMPANY_NOT_ACTIVE");
        }
        for (Session outsider : List.of(w.unassignedAgent(), w.agentElsewhere())) {
            ApiResponse missing = send(outsider, OnBehalf.of(Unique.companyCode(), w.reporter().accountId())
                    .title(title));
            ApiResponse hidden = send(outsider, OnBehalf.of(w.code(), w.reporter().accountId()).title(title));

            assertThat(missing.status()).as(missing.request()).isEqualTo(404);
            assertThat(hidden.status()).as("filing in the %s company by an agent who is not assigned to it: %s",
                    status, hidden.snippet()).isEqualTo(404);
            assertThat(hidden.body()).as("body, like a code that does not exist").isEqualTo(missing.body());
        }
        assertThat(newestTitles(w.admin())).as("titles of the newest issues").doesNotContain(title);
    }

    // S3-AC-35

    @ParameterizedTest(name = "channel {0}")
    @ValueSource(strings = {"PORTAL", "EMAIL", "", "FAX", "(left out)"})
    @DisplayName("S3-AC-35 a channel other than PHONE and OTHER is 400 with field channel, listed next to another wrong field")
    void wrongChannelIsRefused(String channel) {
        World w = flow.world();
        flow.fileOnBehalf(w.agent(), OnBehalf.of(w.code(), w.reporter().accountId()));
        Map<String, Object> body = new LinkedHashMap<>(OnBehalf.of(w.code(), w.reporter().accountId()).body());
        if (channel.equals("(left out)")) {
            body.remove("channel");
        } else {
            body.put("channel", channel);
        }
        Map<String, Object> twoWrong = new LinkedHashMap<>(body);
        twoWrong.put("title", "a".repeat(201));

        ApiResponse refused = w.agent().client().post("/api/console/issues").json(body).send();
        ApiResponse both = w.agent().client().post("/api/console/issues").json(twoWrong).send();

        assertThat(refused.status()).as(refused.request()).isEqualTo(400);
        assertThat(refused.errorCode()).isEqualTo("INVALID_INPUT");
        assertThat(fieldNames(refused)).as("fields for the channel alone").containsExactly("channel");
        assertThat(both.status()).as(both.request()).isEqualTo(400);
        assertThat(both.errorCode()).isEqualTo("INVALID_INPUT");
        assertThat(fieldNames(both)).as("fields for the channel and the title").contains("channel", "title");
        assertThat(newestTitles(w.admin())).as("titles of the newest issues").doesNotContain((String) body.get("title"));
    }

    // S3-AC-36

    @Test
    @DisplayName("S3-AC-36 the console shows createdBy and channel, null and PORTAL for an issue the customer filed; the portal shows only filedBySupport")
    void consoleShowsWhoFiledAndThePortalOnlyThatSupportDid() {
        World w = flow.world();
        Issue forCustomer = flow.fileOnBehalf(w.agent(), OnBehalf.of(w.code(), w.reporter().accountId()));
        Issue byCustomer = fixtures.fileIssue(w.reporter(), w.code());

        JsonNode staffForCustomer = flow.detail(w.admin(), forCustomer.key());
        JsonNode staffByCustomer = flow.detail(w.admin(), byCustomer.key());
        assertThat(staffForCustomer.path("createdBy").path("accountId").asLong(-1)).isEqualTo(w.agent().accountId());
        assertThat(text(staffForCustomer, "channel")).isEqualTo("PHONE");
        assertThat(staffByCustomer.has("createdBy")).as("createdBy is present in %s", staffByCustomer).isTrue();
        assertThat(staffByCustomer.path("createdBy").isNull()).as("createdBy of an issue the customer filed").isTrue();
        assertThat(text(staffByCustomer, "channel")).isEqualTo("PORTAL");

        Map<String, Boolean> onConsoleList = new LinkedHashMap<>();
        items(IssueFlow.list(w.agent(), "companyCode", w.code()))
                .forEach(n -> onConsoleList.put(text(n, "key"), n.path("filedOnBehalf").booleanValue()));
        assertThat(onConsoleList).as("filedOnBehalf in the console list")
                .containsEntry(forCustomer.key(), true).containsEntry(byCustomer.key(), false);

        assertThat(flow.detail(w.reporter(), forCustomer.key()).path("filedBySupport").asBoolean(false))
                .as("filedBySupport of the issue filed for the customer").isTrue();
        assertThat(flow.detail(w.reporter(), byCustomer.key()).path("filedBySupport").asBoolean(true))
                .as("filedBySupport of the issue the customer filed").isFalse();
        Map<String, Boolean> onPortalList = new LinkedHashMap<>();
        items(IssueFlow.list(w.colleague()))
                .forEach(n -> onPortalList.put(text(n, "key"), n.path("filedBySupport").booleanValue()));
        assertThat(onPortalList).as("filedBySupport in the portal list")
                .containsEntry(forCustomer.key(), true).containsEntry(byCustomer.key(), false);
    }

    // S3-AC-38

    @Test
    @DisplayName("S3-AC-38 the customer handles a private issue filed for them like their own: sees it under mine, comments, edits, closes; a colleague gets 404 and does not see it listed")
    void reporterHandlesAnIssueFiledForThem() {
        World w = flow.world();
        Issue issue = flow.fileOnBehalf(w.agent(), OnBehalf.of(w.code(), w.reporter().accountId()).privateIssue());

        ApiResponse colleagueDetail = w.colleague().client().get("/api/portal/issues/" + issue.key()).send();
        assertThat(colleagueDetail.status()).as(colleagueDetail.request()).isEqualTo(404);
        assertThat(colleagueDetail.errorCode()).isEqualTo("NOT_FOUND");
        assertThat(keys(IssueFlow.list(w.colleague()))).as("issues the colleague sees").doesNotContain(issue.key());

        assertThat(keys(IssueFlow.list(w.reporter(), "mine", true))).as("the customer's own issues")
                .contains(issue.key());
        assertThat(flow.detail(w.reporter(), issue.key()).path("reporter").path("accountId").asLong(-1))
                .isEqualTo(w.reporter().accountId());
        fixtures.comment(w.reporter(), issue.key(), "Thanks for taking this down.");
        String newTitle = Unique.text("Put right by the customer");
        assertThat(text(flow.edit(w.reporter(), issue.key(), Map.of("title", newTitle)), "title")).isEqualTo(newTitle);
        assertThat(text(flow.close(w.reporter(), issue.key()), "status")).isEqualTo("CLOSED");
        assertThat(text(flow.detail(w.admin(), issue.key()), "status")).isEqualTo("CLOSED");
    }

    // S3-AC-39

    @Test
    @DisplayName("S3-AC-39 the 11th filing on behalf of one staff account within 60 seconds is 429 with Retry-After and files nothing; after 60 seconds it works")
    void eleventhFilingOnBehalfIsLimited() {
        World w = flow.world();
        for (int i = 0; i < 10; i++) {
            flow.fileOnBehalf(w.agent(), OnBehalf.of(w.code(), w.reporter().accountId()));
        }
        OnBehalf eleventh = OnBehalf.of(w.code(), w.reporter().accountId());

        ApiResponse limited = send(w.agent(), eleventh);

        assertRateLimited(limited);
        assertThat(IssueFlow.total(IssueFlow.list(w.reporter()))).as("issues of the company").isEqualTo(10);
        clock.advance(Duration.ofSeconds(61));
        assertThat(send(w.agent(), eleventh).status()).as("the same filing after 60 seconds").isEqualTo(201);
    }

    @Test
    @DisplayName("S3-AC-39 filings on behalf are counted before the company is looked up: ten 404s use up the limit")
    void filingsOnBehalfAreCountedBeforeTheCompanyIsFound() {
        World w = flow.world();
        Company other = fixtures.createCompany(w.admin());
        Session ofOther = fixtures.customer(w.admin(), other.code());
        flow.fileOnBehalf(w.admin(), OnBehalf.of(other.code(), ofOther.accountId()));
        for (int i = 0; i < 10; i++) {
            ApiResponse notFound = send(w.agent(), OnBehalf.of(other.code(), ofOther.accountId()));
            assertThat(notFound.status()).as(notFound.request()).isEqualTo(404);
        }

        ApiResponse eleventh = send(w.agent(), OnBehalf.of(w.code(), w.reporter().accountId()));

        assertRateLimited(eleventh);
        assertThat(IssueFlow.total(IssueFlow.list(w.reporter()))).as("issues of the company").isZero();
    }
}
