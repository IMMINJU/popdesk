package io.popdesk.sla;

import static io.popdesk.sla.SlaSteps.FIRST_RESPONSE;
import static io.popdesk.sla.SlaSteps.RESOLUTION;
import static io.popdesk.sla.SlaSteps.assertGoal;
import static io.popdesk.sla.SlaSteps.goal;
import static io.popdesk.sla.SlaSteps.sla;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.sla.SlaSteps.Desk;
import io.popdesk.sla.SlaSteps.Policy;
import io.popdesk.support.Fixtures.Issue;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/**
 * Only business time counts (S8A-AC-12), in {@code Asia/Seoul}: business hours on weekdays, a holiday, and every day
 * empty (24 hours) with a holiday. Each case files a NORMAL issue at a wall-clock time, reads both due times, then
 * moves the clock over time that does not count and reads the resolution's remaining minutes. Counting wall-clock
 * time gives earlier due times and fewer minutes left. The day after a summer time change is
 * {@link SlaDaylightSavingTest}; the edges of the calculation are the unit tests of design.md 7절.
 */
class SlaBusinessHoursTest extends SlaAppTest {

    /**
     * One case: the policy (given the filing date), the goals of NORMAL, the filing wall-clock time, the due times as
     * days after the filing date and a time, and a later wall-clock time with the resolution minutes left then.
     */
    record Case(String name, Function<LocalDate, Policy> policy, DayOfWeek filedOn, LocalTime filedAt,
            int firstResponse, int firstDays, LocalTime firstDue, int resolution, int resolutionDays,
            LocalTime resolutionDue, int laterDays, LocalTime later, long leftLater) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Case> cases() {
        return Stream.of(
                // Friday 17:00-18:00 counts 60, then Monday from 09:00.
                new Case("filed an hour before closing on Friday",
                        day -> Policy.off().weekdays("09:00", "18:00"), DayOfWeek.FRIDAY, LocalTime.of(17, 0),
                        30, 0, LocalTime.of(17, 30), 240, 3, LocalTime.of(12, 0),
                        1, LocalTime.of(12, 0), 180),
                // Nothing counts on Saturday and Sunday; both clocks start Monday 09:00.
                new Case("filed on Saturday",
                        day -> Policy.off().weekdays("09:00", "18:00"), DayOfWeek.SATURDAY, LocalTime.of(10, 0),
                        60, 2, LocalTime.of(10, 0), 240, 2, LocalTime.of(13, 0),
                        1, LocalTime.of(22, 0), 240),
                // Monday is a holiday: Friday 60, then Tuesday from 09:00.
                new Case("filed on Friday before a holiday Monday",
                        day -> Policy.off().weekdays("09:00", "18:00").holiday(day.plusDays(3)), DayOfWeek.FRIDAY,
                        LocalTime.of(17, 0), 120, 4, LocalTime.of(10, 0), 300, 4, LocalTime.of(13, 0),
                        3, LocalTime.of(15, 0), 240),
                // No business hours: 24 hours a day, but not the Saturday holiday.
                new Case("every day 24 hours, filed late on Friday before a holiday Saturday",
                        day -> Policy.off().holiday(day.plusDays(1)), DayOfWeek.FRIDAY, LocalTime.of(23, 0),
                        30, 0, LocalTime.of(23, 30), 120, 2, LocalTime.of(1, 0),
                        1, LocalTime.of(12, 0), 60));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("S8A-AC-12 the due times count business time only, from the next opening when filed outside "
            + "business hours, skipping holidays, and 24 hours a day when no weekday has business hours; time "
            + "outside them does not use up the goal")
    void onlyBusinessTimeCounts(Case c) {
        Instant filedAt = sla.moveToNext(c.filedOn(), c.filedAt());
        LocalDate day = sla.local(filedAt).toLocalDate();
        Desk desk = sla.desk();
        SlaSteps.change(desk.admin(), c.policy().apply(day)
                .target("NORMAL", c.firstResponse(), c.resolution()));

        Issue issue = sla.file(desk, "NORMAL");

        assertThat(SlaSteps.createdAt(issue)).as("createdAt").isEqualTo(filedAt);
        JsonNode filed = sla(desk.agent(), issue.key());
        assertGoal(goal(filed, FIRST_RESPONSE), "OK", sla.at(day.plusDays(c.firstDays()), c.firstDue()),
                (long) c.firstResponse(), "first response at filing");
        Instant resolutionDue = sla.at(day.plusDays(c.resolutionDays()), c.resolutionDue());
        assertGoal(goal(filed, RESOLUTION), "OK", resolutionDue, (long) c.resolution(), "resolution at filing");

        sla.advanceTo(sla.at(day.plusDays(c.laterDays()), c.later()));
        Desk again = sla.signInAgain(desk);
        assertGoal(goal(sla(again.agent(), issue.key()), RESOLUTION), "OK", resolutionDue, c.leftLater(),
                "resolution at " + sla.now());
    }
}
