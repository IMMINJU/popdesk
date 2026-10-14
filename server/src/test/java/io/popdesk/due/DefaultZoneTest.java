package io.popdesk.due;

import static io.popdesk.due.DueSteps.UTC;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.notify.Delivery;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.ReceivedMail;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;

/**
 * An installation without {@code POPDESK_TIMEZONE}: the setup "connected" as it is. At UTC 23:30 the
 * date is already the next day in every zone from UTC+1 east, the machine's own zone among them when it
 * is one of those, so an app that judged in the machine's zone would refuse the UTC date.
 */
class DefaultZoneTest extends ConnectedAppTest {

    private static final DateTimeFormatter SENT_AT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT)
            .withZone(UTC);

    @Autowired
    private Environment environment;

    private DueSteps due;

    @BeforeEach
    void setUp() {
        due = new DueSteps(fixtures, clock);
    }

    @Test
    @DisplayName("S6A-AC-72 without POPDESK_TIMEZONE the due date is judged in UTC and the test mail gives its time "
            + "in UTC")
    void utcWithoutTheSetting() {
        assertThat(environment.getProperty("POPDESK_TIMEZONE")).as("POPDESK_TIMEZONE of this context").isNull();
        due.moveToUtcHour(23);
        Session admin = due.flow().admin();
        Instant now = clock.instant();

        JsonNode result = Delivery.testMail(admin);

        assertThat(result.path("ok").asBoolean(false)).as("ok of %s", result).isTrue();
        String time = SENT_AT.format(now) + " (UTC)";
        List<String> texts = mail().messagesTo(admin.email()).stream().map(ReceivedMail::text).toList();
        assertThat(texts).as("texts of the mails to the admin").anyMatch(text -> text.contains(time));
        due.assertTodayIsJudgedIn(UTC);
    }
}
