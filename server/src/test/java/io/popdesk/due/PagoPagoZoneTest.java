package io.popdesk.due;

import static io.popdesk.due.DueSteps.UTC;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.BareAppTest;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * An installation at UTC-11 ({@code Pacific/Pago_Pago}), the half of S6A-AC-34 west of UTC. From UTC
 * midnight to 11:00 its date is the day before the UTC date.
 */
@TestPropertySource(properties = "POPDESK_TIMEZONE=" + PagoPagoZoneTest.ZONE)
class PagoPagoZoneTest extends BareAppTest {

    static final String ZONE = "Pacific/Pago_Pago";

    private DueSteps due;

    @BeforeEach
    void setUp() {
        due = new DueSteps(fixtures, clock);
    }

    @Test
    @DisplayName("S6A-AC-34 at UTC 05:30 in Pacific/Pago_Pago the zone's today, the day before the UTC date, is "
            + "taken and its yesterday is 400 OUT_OF_RANGE")
    void todayIsTheZonesDate() {
        due.moveToUtcHour(5);
        assertThat(due.today(ZoneId.of(ZONE))).as("the zone's date at UTC 05:30").isEqualTo(due.today(UTC).minusDays(1));

        due.assertTodayIsJudgedIn(ZoneId.of(ZONE));
    }
}
