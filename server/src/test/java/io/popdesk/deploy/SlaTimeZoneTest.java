package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.deploy.AppSteps.Person;
import io.popdesk.sla.SlaSteps;
import io.popdesk.sla.SlaSteps.Policy;
import io.popdesk.support.AuditChainMath;
import io.popdesk.support.Wait;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The installation time zone changes under a policy (S8A-AC-07, and S8A-AC-06's version the system makes for it):
 * the image starts with {@code POPDESK_TIMEZONE=Asia/Seoul}, an admin sets a policy, and the app is started again on
 * the same database volume with {@code America/New_York}. The next run of {@code sla-recalculate}, a minute of real
 * time at most, makes one new version with the new zone and the same goals, business hours, and holidays, changed by
 * the system; it leaves no audit entry. One context of the test JVM cannot show this: each has its own database.
 */
@Tag("deploy")
class SlaTimeZoneTest {

    private static final String SEOUL = "Asia/Seoul";
    private static final String NEW_YORK = "America/New_York";

    /** The job's period, a minute of real time, and a few rounds of the scheduler. */
    private static final Duration NEXT_RUN = Duration.ofSeconds(90);

    private static JsonNode policy(Person admin) {
        return admin.client().get(SlaSteps.POLICY).expect(200).json();
    }

    private static long auditEntries(Person admin) {
        return admin.client().get(AuditFile.LOG).query("action", "SLA_POLICY_CHANGED").query("size", 1)
                .expect(200).jsonLong("totalElements");
    }

    @Test
    @DisplayName("S8A-AC-06, S8A-AC-07 started again with another POPDESK_TIMEZONE, the app makes one new policy "
            + "version within a minute with the new zone, the same goals, business hours, and holidays, and no "
            + "changer, and leaves no SLA_POLICY_CHANGED entry for it")
    void anotherZoneMakesASystemVersion() {
        try (Stack stack = Stack.release("slazone")) {
            stack.appEnv("POPDESK_TIMEZONE", SEOUL);
            stack.up();
            stack.awaitHealthy("app");
            Person admin = new AppSteps(stack).signInAdmin();
            Policy set = Policy.off().weekdays("09:00", "18:00").day(DayOfWeek.SATURDAY, "10:00", "14:00")
                    .target("NORMAL", 60, 480).target("URGENT", 15, 120).holiday(LocalDate.of(2026, 12, 25));
            JsonNode changed = admin.client().put(SlaSteps.POLICY).json(set.body(SlaSteps.version(policy(admin))))
                    .expect(200).json();
            assertThat(changed.path("timeZone").asString(null)).as("timeZone of the admin's version").isEqualTo(SEOUL);
            long entries = auditEntries(admin);
            assertThat(entries).as("SLA_POLICY_CHANGED entries after the admin's change").isEqualTo(1);

            stack.appEnv("POPDESK_TIMEZONE", NEW_YORK);
            stack.up("app");
            stack.awaitHealthy("app");
            Person again = new AppSteps(stack).signInAdmin();
            JsonNode moved = Wait.until("a policy version for " + NEW_YORK, NEXT_RUN, () -> {
                JsonNode now = policy(again);
                return SlaSteps.version(now) > SlaSteps.version(changed) ? now : null;
            });

            assertThat(SlaSteps.version(moved)).as("version after the restart").isEqualTo(SlaSteps.version(changed) + 1);
            assertThat(moved.path("timeZone").asString(null)).as("timeZone of the new version").isEqualTo(NEW_YORK);
            assertThat(moved.path("changedBy").isNull()).as("changedBy of %s is null", moved).isTrue();
            for (String part : new String[] {"targets", "hours", "holidays"}) {
                assertThat(AuditChainMath.canonical(moved.path(part))).as("%s of the new version", part)
                        .isEqualTo(AuditChainMath.canonical(changed.path(part)));
            }
            assertThat(auditEntries(again)).as("SLA_POLICY_CHANGED entries after the new version").isEqualTo(entries);
        }
    }
}
