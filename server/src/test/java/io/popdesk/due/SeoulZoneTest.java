package io.popdesk.due;

import static io.popdesk.due.DueSteps.UTC;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.BareAppTest;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;

/**
 * {@code POPDESK_TIMEZONE} with spaces around an IANA zone name: the app takes it without them. The
 * value is read as a properties line, so the leading space is escaped.
 */
@TestPropertySource(properties = "POPDESK_TIMEZONE=\\ Asia/Seoul ")
class SeoulZoneTest extends BareAppTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired
    private Environment environment;

    private DueSteps due;

    @BeforeEach
    void setUp() {
        due = new DueSteps(fixtures, clock);
    }

    @Test
    @DisplayName("S6A-AC-71 \" Asia/Seoul \" is taken without its spaces: at UTC 20:30 Seoul's today, the day after "
            + "the UTC date, is taken and its yesterday is 400 OUT_OF_RANGE")
    void zoneNameWithSpaces() {
        assertThat(environment.getProperty("POPDESK_TIMEZONE")).as("POPDESK_TIMEZONE of this context")
                .isEqualTo(" Asia/Seoul ");
        due.moveToUtcHour(20);
        assertThat(due.today(SEOUL)).as("Seoul's date at UTC 20:30").isEqualTo(due.today(UTC).plusDays(1));

        due.assertTodayIsJudgedIn(SEOUL);
    }
}
