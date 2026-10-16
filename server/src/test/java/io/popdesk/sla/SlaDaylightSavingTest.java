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
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The day summer time ends (S8A-AC-12), on a context of this class alone with {@code POPDESK_TIMEZONE} set to
 * {@code America/New_York}: on Sunday 2026-11-01 the clocks go from 02:00 EDT back to 01:00 EST, so the wall-clock
 * span 00:00-12:00 of that day lasts 13 hours. Business hours are that span on Sundays only.
 *
 * <p>Filed at 00:00 EDT (04:00Z), 150 minutes run out at 06:30Z (01:30 EST, the second 01:30) and 750 at 16:30Z
 * (11:30 EST). Counting wall-clock minutes gives 02:30 and leaves 750 for the next Sunday; a fixed offset of -4
 * makes the span end at 16:00Z and also leaves 750 for the next Sunday.
 */
@TestPropertySource(properties = "POPDESK_TIMEZONE=" + SlaDaylightSavingTest.NEW_YORK)
class SlaDaylightSavingTest extends SlaAppTest {

    static final String NEW_YORK = "America/New_York";

    private static final Instant MIDNIGHT = Instant.parse("2026-11-01T04:00:00Z");

    @Override
    protected ZoneId zone() {
        return ZoneId.of(NEW_YORK);
    }

    @Test
    @DisplayName("S8A-AC-12 on 2026-11-01 in America/New_York, when summer time ends, the goals count the real time "
            + "of the business hours 00:00-12:00: filed at 00:00, 150 minutes run out at 01:30 EST and 750 at 11:30 "
            + "EST, and two real hours later 120 minutes are used")
    void summerTimeEnds() {
        sla.advanceTo(MIDNIGHT);
        Desk desk = sla.desk();
        SlaSteps.change(desk.admin(), Policy.off().day(DayOfWeek.SUNDAY, "00:00", "12:00").target("NORMAL", 150, 750));

        Issue issue = sla.file(desk, "NORMAL");

        assertThat(SlaSteps.createdAt(issue)).as("createdAt").isEqualTo(MIDNIGHT);
        JsonNode filed = sla(desk.agent(), issue.key());
        assertGoal(goal(filed, FIRST_RESPONSE), "OK", Instant.parse("2026-11-01T06:30:00Z"), 150L,
                "first response at filing");
        assertGoal(goal(filed, RESOLUTION), "OK", Instant.parse("2026-11-01T16:30:00Z"), 750L,
                "resolution at filing");

        // 01:00 EST: two real hours after 00:00 EDT, one by the wall clock.
        sla.advanceTo(Instant.parse("2026-11-01T06:00:00Z"));
        JsonNode later = sla(desk.agent(), issue.key());
        assertThat(SlaSteps.remainingMinutes(goal(later, FIRST_RESPONSE))).as("first response minutes left at "
                + "01:00 EST").isEqualTo(30L);
        assertThat(SlaSteps.remainingMinutes(goal(later, RESOLUTION))).as("resolution minutes left at 01:00 EST")
                .isEqualTo(630L);
    }
}
