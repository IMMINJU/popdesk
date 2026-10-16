package io.popdesk.sla;

import static io.popdesk.sla.SlaSteps.canonical;
import static io.popdesk.sla.SlaSteps.json;
import static io.popdesk.sla.SlaSteps.listedSla;
import static io.popdesk.sla.SlaSteps.policy;
import static io.popdesk.sla.SlaSteps.sla;
import static io.popdesk.sla.SlaSteps.version;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.IssueFlow.OnBehalf;
import io.popdesk.sla.SlaSteps.Policy;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * An installation where nobody ever set the SLA policy (S8A-AC-01), on a context of this class alone: every other SLA
 * class changes the policy. Its one test reads the untouched policy first, so it holds S8A-AC-11's issues filed
 * before SLA was ever on, and the first run of {@code sla-recalculate} after the install (the half of S8A-AC-07 one
 * context can show). The three share the one moment a test can see the untouched state, so they are one test.
 */
@TestPropertySource(properties = SlaUntouchedInstallTest.UNTOUCHED)
class SlaUntouchedInstallTest extends SlaAppTest {

    /** A property the app does not read; it makes this context one of its own. */
    static final String UNTOUCHED = "sla.test.install=untouched";

    private static final Policy ON = Policy.off()
            .target("LOW", 480, 4800)
            .target("NORMAL", 60, 480)
            .target("HIGH", 30, 240)
            .target("URGENT", 15, 120);

    @Test
    @DisplayName("S8A-AC-01, S8A-AC-07, S8A-AC-11 an untouched install reads version 1 with four priorities "
            + "without goals, seven weekdays without hours, no holiday, the app's zone, and no changer, also after "
            + "the first run of sla-recalculate, which fills in the zone of version 1 without a new version; issues "
            + "filed then (by a customer and on behalf) have sla null, and keep it after SLA is turned on and "
            + "changed and the job ran, while an issue filed after turning it on is under SLA")
    void untouchedInstall() {
        Session admin = fixtures.signInAdmin();
        String code = fixtures.createCompany(admin).code();
        Session customer = fixtures.customer(admin, code);
        Session agent = fixtures.agent(admin, code);

        JsonNode first = policy(admin);
        sla.recalculateNow(admin);
        JsonNode afterRun = policy(admin);

        for (JsonNode untouched : List.of(first, afterRun)) {
            assertThat(version(untouched)).as("version of %s", untouched).isEqualTo(1);
            assertThat(canonical(untouched.path("targets"))).as("targets").isEqualTo(canonical(json(Policy.off().targets())));
            assertThat(canonical(untouched.path("hours"))).as("hours").isEqualTo(canonical(json(Policy.off().hours())));
            assertThat(canonical(untouched.path("holidays"))).as("holidays").isEqualTo(canonical(json(List.of())));
            assertThat(untouched.path("timeZone").asString(null)).as("timeZone").isEqualTo(SEOUL);
            assertThat(untouched.path("changedBy").isNull()).as("changedBy of %s is null", untouched).isTrue();
            assertThat(IssueFlow.time(untouched, "changedAt")).as("changedAt").isNotNull();
        }
        assertThat(canonical(afterRun)).as("the policy after the first run").isEqualTo(canonical(first));
        // The API gives the app's zone before the first run too; only the row shows that the run filled it in.
        Map<String, Object> versionOne = db.row("select time_zone from sla_policy where version = 1");
        assertThat(versionOne.get("time_zone")).as("time_zone of version 1 after the first run").isEqualTo(SEOUL);

        Issue filed = fixtures.fileIssue(customer, NewIssue.in(code).priority("URGENT"));
        Issue onBehalf = flow.fileOnBehalf(agent, OnBehalf.of(code, customer.accountId()).priority("HIGH"));
        SlaSteps.change(admin, ON);
        Issue later = fixtures.fileIssue(customer, NewIssue.in(code).priority("URGENT"));
        sla.recalculateNow(admin);
        SlaSteps.change(admin, Policy.off().target("URGENT", 5, 60).target("HIGH", 10, 60));
        sla.recalculateNow(admin);

        for (Issue off : List.of(filed, onBehalf)) {
            assertThat(sla(admin, off.key()).isNull()).as("sla of %s in the detail", off.key()).isTrue();
            assertThat(listedSla(admin, code, off.key()).isNull()).as("sla of %s in the list", off.key()).isTrue();
        }
        assertThat(sla(admin, later.key()).isObject()).as("sla of %s, filed after turning SLA on", later.key())
                .isTrue();
    }
}
