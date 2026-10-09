package io.popdesk.start;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.DbInspector.Column;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Times stored as timestamptz, read from the injected Clock, sent with an offset, and periods as differences. */
class TimeTest extends BareAppTest {

    /** ISO-8601 date and time with seconds, optional fraction, and an offset ({@code Z} or {@code +hh:mm}). */
    static final String WITH_OFFSET = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})";

    @Test
    @DisplayName("S0-AC-12 time columns of business tables are timestamptz, none without a time zone")
    void timeColumnsAreTimestamptz() {
        List<String> withoutZone = new ArrayList<>();
        int withZone = 0;
        for (String table : db.tables()) {
            if (TenantIdRule.EXCEPTIONS.contains(table)) {
                continue;
            }
            for (Column column : db.columns(table)) {
                if (column.dataType().equals("timestamp with time zone")) {
                    withZone++;
                } else if (column.dataType().startsWith("timestamp") || column.dataType().startsWith("time ")) {
                    withoutZone.add(table + "." + column.name() + " " + column.dataType());
                }
            }
        }
        assertThat(withZone).as("timestamptz columns in business tables of %s", db.schema()).isPositive();
        assertThat(withoutZone).as("time columns that are not timestamptz").isEmpty();
    }

    @Test
    @DisplayName("S0-AC-12 the API sends times with an offset, read from the injected Clock")
    void apiTimesHaveOffsetAndComeFromClock() {
        Session admin = fixtures.signInAdmin();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        Company company = fixtures.createCompany(admin);
        String createdAt = company.json().path("createdAt").asString("");
        ApiResponse read = admin.client().get("/api/console/companies/" + company.code()).expect(200);
        String readCreatedAt = read.json().path("createdAt").asString("");

        for (String value : List.of(createdAt, readCreatedAt)) {
            assertThat(value).as("createdAt of company %s", company.code()).matches(WITH_OFFSET);
            assertThat(OffsetDateTime.parse(value).toInstant()).as("createdAt %s vs the Clock", value).isEqualTo(now);
        }
    }

    @Test
    @DisplayName("S0-AC-12 a session lasts 8 hours by the Clock: one second before it ends it works, one second after it is 401")
    void sessionPeriodByClock() {
        Session admin = fixtures.signInAdmin();

        clock.advance(Duration.ofHours(8).minusSeconds(1));
        ApiResponse inside = admin.client().get("/api/console/me").send();
        assertThat(inside.status()).as(inside + " " + inside.snippet()).isEqualTo(200);

        clock.advance(Duration.ofSeconds(2));
        ApiResponse outside = admin.client().get("/api/console/me").send();
        assertThat(outside.status()).as(outside + " " + outside.snippet()).isEqualTo(401);
        assertThat(outside.errorCode()).isEqualTo("UNAUTHENTICATED");
    }
}
