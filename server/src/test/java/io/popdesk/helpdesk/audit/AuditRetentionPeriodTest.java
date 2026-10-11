package io.popdesk.helpdesk.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Which values of {@code POPDESK_AUDIT_RETENTION} are taken, for those the start tests do not try. */
class AuditRetentionPeriodTest {

    @Test
    @DisplayName("S1B-AC-19 without the key the period is 365 days of 24 hours")
    void defaultIsAYearOfDays() {
        assertThat(AuditRetention.parse(null)).contains(Duration.ofHours(365 * 24));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"P30D", "PT720H", " P90D ", "p365d", "P400DT12H"})
    @DisplayName("S1B-AC-20 a duration of days and time of 30 days or more is taken, spaces around it and its case "
            + "aside")
    void thirtyDaysOrMoreIsTaken(String value) {
        assertThat(AuditRetention.parse(value)).isPresent();
        assertThat(AuditRetention.parse(value).orElseThrow()).isGreaterThanOrEqualTo(Duration.ofDays(30));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"", " ", "P29D", "PT719H59M59.999S", "P0D", "P-365D", "-P365D", "365", "365d", "P1Y",
            "P12M", "one year", "P99999999999999999999D"})
    @DisplayName("S1B-AC-20 an empty value, a shorter or negative period, a number, years or months, and a period "
            + "too long to hold are not taken")
    void otherValuesAreNotTaken(String value) {
        assertThat(AuditRetention.parse(value)).isEmpty();
    }
}
