package io.popdesk.sla;

import static io.popdesk.due.DueSteps.assertRefused;
import static io.popdesk.due.DueSteps.fieldCode;
import static io.popdesk.due.DueSteps.keys;
import static io.popdesk.issue.IssueFlow.list;
import static io.popdesk.issue.IssueFlow.listRequest;
import static io.popdesk.issue.IssueFlow.total;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.due.DueSteps;
import io.popdesk.sla.SlaSteps.Policy;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The SLA filter and order of the console issue list (S8A-AC-31, S8A-AC-32, S8A-AC-34). Every day counts 24 hours, so
 * each goal runs out its minutes after filing. Each test lists companies it made ({@code companyCode}), or an agent
 * assigned to its company alone, so issues of other tests do not count.
 *
 * <p>The goals: NORMAL 100 minutes to the first response and 1000 to resolution, HIGH no first response goal and 100
 * to resolution, URGENT 100 and 125, LOW none. At 105 minutes after filing a NORMAL issue has its first response
 * breached and resolution ok, a HIGH issue its resolution breached, an URGENT one its first response breached and
 * resolution in warning (84%); at 85 minutes NORMAL is in warning by its first response and HIGH by its resolution.
 */
class SlaListTest extends SlaAppTest {

    private static final Policy GOALS = Policy.off()
            .target("NORMAL", 100, 1000)
            .target("HIGH", null, 100)
            .target("URGENT", 100, 125);

    /** An admin, a company with a customer and an agent assigned to it, and the policy {@link #GOALS}. */
    private record Scene(Session admin, String code, Session customer, Session agent) {}

    private Scene scene() {
        Session admin = fixtures.signInAdmin();
        String code = fixtures.createCompany(admin).code();
        Scene s = new Scene(admin, code, fixtures.customer(admin, code), fixtures.agent(admin, code));
        SlaSteps.change(admin, GOALS);
        flow.tick();
        return s;
    }

    private Issue file(Scene s, String priority) {
        return fixtures.fileIssue(s.customer(), NewIssue.in(s.code()).priority(priority));
    }

    // S8A-AC-31

    @Test
    @DisplayName("S8A-AC-31 sla=breached lists the issues with a running goal past its due time, by either goal, "
            + "sla=warning those past 80% with none breached, each with its totalElements; issues with stopped "
            + "clocks, without goals, or not past 80% match neither; both go with a status filter")
    void filterByTheWorseGoal() {
        Scene s = scene();
        Issue normalBreached = file(s, "NORMAL");
        Issue highBreached = file(s, "HIGH");
        Issue urgentBreached = file(s, "URGENT");
        Issue low = file(s, "LOW");
        Issue answered = file(s, "NORMAL");
        fixtures.comment(s.agent(), answered.key(), "First answer");
        Issue resolved = file(s, "URGENT");
        flow.moveTo(s.agent(), resolved.key(), "RESOLVED");
        sla.advanceMinutes(20);
        Issue normalWarning = file(s, "NORMAL");
        Issue highWarning = file(s, "HIGH");
        sla.advanceMinutes(65);
        Issue fresh = file(s, "URGENT");
        Issue okNow = file(s, "NORMAL");
        sla.advanceMinutes(20);
        flow.moveTo(s.agent(), normalBreached.key(), "IN_PROGRESS");
        flow.moveTo(s.agent(), normalWarning.key(), "IN_PROGRESS");

        ApiResponse breached = list(s.agent(), "companyCode", s.code(), "sla", "breached");
        ApiResponse warning = list(s.agent(), "companyCode", s.code(), "sla", "warning");
        ApiResponse breachedInProgress = list(s.agent(), "companyCode", s.code(), "sla", "breached", "status",
                "IN_PROGRESS");
        ApiResponse warningReceived = list(s.agent(), "companyCode", s.code(), "sla", "warning", "status", "RECEIVED");

        assertThat(keys(breached)).as("sla=breached").containsExactlyInAnyOrder(normalBreached.key(),
                highBreached.key(), urgentBreached.key());
        assertThat(total(breached)).as("totalElements of sla=breached").isEqualTo(3);
        assertThat(keys(warning)).as("sla=warning").containsExactlyInAnyOrder(normalWarning.key(), highWarning.key());
        assertThat(total(warning)).as("totalElements of sla=warning").isEqualTo(2);
        assertThat(keys(breachedInProgress)).as("sla=breached with status IN_PROGRESS")
                .containsExactly(normalBreached.key());
        assertThat(total(breachedInProgress)).as("its totalElements").isEqualTo(1);
        assertThat(keys(warningReceived)).as("sla=warning with status RECEIVED").containsExactly(highWarning.key());
        assertThat(total(list(s.agent(), "companyCode", s.code()))).as("the company's issues without sla")
                .isEqualTo(10);
        assertThat(keys(list(s.agent(), "companyCode", s.code()))).as("the company's issues")
                .contains(low.key(), answered.key(), resolved.key(), fresh.key(), okNow.key());
    }

    @ParameterizedTest(name = "sla={0}")
    @ValueSource(strings = {"Breached", "ok", "met", "overdue", "breached,warning"})
    @DisplayName("S8A-AC-31 any other value of sla is 400 INVALID_INPUT with sla UNKNOWN_VALUE")
    void unknownSla(String value) {
        Session admin = fixtures.signInAdmin();

        ApiResponse rejected = listRequest(admin, "sla", value).send();
        ApiResponse taken = listRequest(admin, "sla", "warning", "page", 1).send();

        assertRefused(rejected, 400, "INVALID_INPUT");
        assertThat(fieldCode(rejected, "sla")).as("code of sla in %s", rejected.snippet()).isEqualTo("UNKNOWN_VALUE");
        assertThat(taken.status()).as("sla=warning: %s", taken.snippet()).isEqualTo(200);
    }

    // S8A-AC-32

    @Test
    @DisplayName("S8A-AC-32 sort=slaDue lists the earliest running due time of either goal first and issues without "
            + "one last, newest first; sort=dueDate still orders by the due date people set")
    void slaDueOrder() {
        Scene s = scene();
        Issue first = file(s, "NORMAL");       // first response due at +100
        sla.advanceMinutes(10);
        Issue second = file(s, "HIGH");        // resolution due at +110
        Issue noGoal = file(s, "LOW");
        sla.advanceMinutes(10);
        Issue answered = file(s, "NORMAL");    // first response met; resolution due at +1020
        fixtures.comment(s.agent(), answered.key(), "First answer");
        Issue resolved = file(s, "NORMAL");    // both clocks stopped
        flow.moveTo(s.agent(), resolved.key(), "RESOLVED");
        sla.advanceMinutes(5);
        Issue third = file(s, "URGENT");       // first response due at +125, resolution at +150
        LocalDate today = sla.now().toLocalDate();
        DueSteps.set(s.agent(), first.key(), today.plusDays(3));
        DueSteps.set(s.agent(), third.key(), today.plusDays(1));

        ApiResponse bySlaDue = list(s.agent(), "companyCode", s.code(), "sort", "slaDue");
        ApiResponse byDueDate = list(s.agent(), "companyCode", s.code(), "sort", "dueDate");

        assertThat(keys(bySlaDue)).as("sort=slaDue").containsExactly(first.key(), second.key(), third.key(),
                answered.key(), resolved.key(), noGoal.key());
        assertThat(total(bySlaDue)).as("totalElements with sort=slaDue").isEqualTo(6);
        assertThat(keys(byDueDate)).as("sort=dueDate").containsExactly(third.key(), first.key(), resolved.key(),
                answered.key(), noGoal.key(), second.key());
    }

    // S8A-AC-34

    @Test
    @DisplayName("S8A-AC-34 an agent's list with sla=breached or sort=slaDue holds only issues of the companies "
            + "assigned to the agent: a breached issue of another company is neither listed nor counted, also when "
            + "that company is asked for")
    void filterStaysInTheAgentsCompanies() {
        Scene s = scene();
        String other = fixtures.createCompany(s.admin()).code();
        Session otherCustomer = fixtures.customer(s.admin(), other);
        Issue mine = file(s, "NORMAL");
        Issue notMine = fixtures.fileIssue(otherCustomer, NewIssue.in(other).priority("NORMAL"));
        sla.advanceMinutes(105);

        ApiResponse breached = list(s.agent(), "sla", "breached");
        ApiResponse otherCompany = list(s.agent(), "sla", "breached", "companyCode", other);
        ApiResponse bySlaDue = list(s.agent(), "sort", "slaDue");
        ApiResponse adminView = list(s.admin(), "sla", "breached", "companyCode", other);

        assertThat(keys(breached)).as("the agent's sla=breached").containsExactly(mine.key());
        assertThat(total(breached)).as("its totalElements").isEqualTo(1);
        assertThat(keys(otherCompany)).as("sla=breached for the other company").isEmpty();
        assertThat(total(otherCompany)).as("its totalElements").isZero();
        assertThat(keys(bySlaDue)).as("the agent's sort=slaDue").containsExactly(mine.key());
        assertThat(keys(adminView)).as("the admin's sla=breached for the other company")
                .containsExactly(notMine.key());
    }
}
