package io.popdesk.due;

import static io.popdesk.due.DueSteps.UTC;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.notify.Delivery;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.ReceivedMail;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * An installation at UTC+14 ({@code Pacific/Kiritimati}), the half of S6A-AC-34 east of UTC. From UTC
 * 10:00 to midnight its date is the day after the UTC date. Connected, for the test mail of S6A-AC-72.
 */
@TestPropertySource(properties = "POPDESK_TIMEZONE=" + KiritimatiZoneTest.ZONE)
class KiritimatiZoneTest extends ConnectedAppTest {

    static final String ZONE = "Pacific/Kiritimati";

    private static final DateTimeFormatter SENT_AT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT);

    private DueSteps due;

    @BeforeEach
    void setUp() {
        due = new DueSteps(fixtures, clock);
    }

    @Test
    @DisplayName("S6A-AC-34 at UTC 12:30 in Pacific/Kiritimati the zone's today is taken and its yesterday, which "
            + "is the UTC date, is 400 OUT_OF_RANGE")
    void todayIsTheZonesDate() {
        due.moveToUtcHour(12);
        assertThat(due.today(ZoneId.of(ZONE))).as("the zone's date at UTC 12:30").isEqualTo(due.today(UTC).plusDays(1));

        due.assertTodayIsJudgedIn(ZoneId.of(ZONE));
    }

    @Test
    @DisplayName("S6A-AC-72 the test mail gives its time in Pacific/Kiritimati, the zone the due date is judged in")
    void testMailAndDueDateUseTheSameZone() {
        due.moveToUtcHour(12);
        Session admin = due.flow().admin();
        Instant now = clock.instant();

        JsonNode result = Delivery.testMail(admin);

        assertThat(result.path("ok").asBoolean(false)).as("ok of %s", result).isTrue();
        String time = SENT_AT.withZone(ZoneId.of(ZONE)).format(now) + " (" + ZONE + ")";
        List<String> texts = mail().messagesTo(admin.email()).stream().map(ReceivedMail::text).toList();
        assertThat(texts).as("texts of the mails to the admin").anyMatch(text -> text.contains(time));
        due.assertTodayIsJudgedIn(ZoneId.of(ZONE));
    }
}
