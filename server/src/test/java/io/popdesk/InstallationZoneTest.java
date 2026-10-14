package io.popdesk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Which {@code POPDESK_TIMEZONE} values popdesk takes (S6A-AC-71, S6A-AC-72) and which date is "today"
 * in a zone (S6A-AC-34). Written from the Javadoc of {@link InstallationZone} alone. What an app started
 * with a wrong value does (S6A-AC-70) is not tested here.
 */
class InstallationZoneTest {

    // ---- parse ----

    @Test
    @DisplayName("S6A-AC-72 without POPDESK_TIMEZONE the zone is UTC")
    void missingKeyIsUtc() {
        assertThat(InstallationZone.parse(null)).contains(InstallationZone.DEFAULT);
        assertThat(InstallationZone.DEFAULT.getId()).isEqualTo("UTC");
    }

    @ParameterizedTest(name = "S6A-AC-71 POPDESK_TIMEZONE=\"{0}\" is UTC")
    @ValueSource(strings = {"UTC", " UTC", "UTC ", "\tUTC\n", "  UTC  "})
    @DisplayName("S6A-AC-71 UTC is taken, with or without spaces around it")
    void utcIsTaken(String configured) {
        assertThat(InstallationZone.parse(configured)).contains(InstallationZone.DEFAULT);
        assertThat(InstallationZone.parse(configured)).get().extracting(ZoneId::getId).isEqualTo("UTC");
    }

    @ParameterizedTest(name = "S6A-AC-71 POPDESK_TIMEZONE=\"{0}\" is {1}")
    @CsvSource(value = {
            "Asia/Seoul|Asia/Seoul",
            "America/New_York|America/New_York",
            "Europe/London|Europe/London",
            "Pacific/Kiritimati|Pacific/Kiritimati",
            "Pacific/Pago_Pago|Pacific/Pago_Pago",
            "America/Argentina/Buenos_Aires|America/Argentina/Buenos_Aires",
            " Asia/Seoul |Asia/Seoul",
            "  Asia/Seoul|Asia/Seoul",
            "Asia/Seoul  |Asia/Seoul",
            "\tAsia/Seoul\t|Asia/Seoul"},
            delimiter = '|', ignoreLeadingAndTrailingWhitespace = false)
    @DisplayName("S6A-AC-71 an IANA region name is taken, and the spaces around it do not matter")
    void regionNameIsTaken(String configured, String zone) {
        assertThat(InstallationZone.parse(configured)).contains(ZoneId.of(zone));
    }

    @ParameterizedTest(name = "S6A-AC-70 POPDESK_TIMEZONE=\"{0}\" is not taken")
    @ValueSource(strings = {
        "", " ", "\t", "  \n ",
        "utc", "Utc", "uTC",
        "+09:00", "-05:00", "+0900", "+9", "GMT+9", "GMT+09:00", "UTC+9", "UTC+09:00", "UT+9", "Z",
        "KST", "EST", "PST", "GMT", "UT",
        "Asia/Nowhere", "Nowhere/Seoul", "asia/seoul", "ASIA/SEOUL", "Asia/seoul",
        "Asia Seoul", "Asia/ Seoul", "Asia /Seoul", "Seoul", "/", "Asia/Seoul/"
    })
    @DisplayName("S6A-AC-70 empty, blank, utc in other case, offsets, short names, and unknown names are not taken")
    void otherValuesAreRefused(String configured) {
        assertThat(InstallationZone.parse(configured)).isNotNull().isEmpty();
    }

    // ---- today ----

    static Stream<Arguments> datesInZones() {
        return Stream.of(
                Arguments.of("2026-10-15T12:30:00Z", "Pacific/Kiritimati", "2026-10-16"),
                Arguments.of("2026-10-15T05:30:00Z", "Pacific/Pago_Pago", "2026-10-14"),
                Arguments.of("2026-10-15T12:30:00Z", "UTC", "2026-10-15"),
                Arguments.of("2026-10-15T05:30:00Z", "UTC", "2026-10-15"),
                Arguments.of("2026-10-15T09:59:59Z", "Pacific/Kiritimati", "2026-10-15"),
                Arguments.of("2026-10-15T10:00:00Z", "Pacific/Kiritimati", "2026-10-16"),
                Arguments.of("2026-10-15T10:59:59Z", "Pacific/Pago_Pago", "2026-10-14"),
                Arguments.of("2026-10-15T11:00:00Z", "Pacific/Pago_Pago", "2026-10-15"),
                Arguments.of("2026-10-15T14:59:59Z", "Asia/Seoul", "2026-10-15"),
                Arguments.of("2026-10-15T15:00:00Z", "Asia/Seoul", "2026-10-16"),
                Arguments.of("2026-12-31T15:00:00Z", "Asia/Seoul", "2027-01-01"),
                Arguments.of("2027-01-01T04:59:59Z", "America/New_York", "2026-12-31"),
                Arguments.of("2027-01-01T05:00:00Z", "America/New_York", "2027-01-01"),
                Arguments.of("2028-02-29T23:00:00Z", "Asia/Seoul", "2028-03-01"));
    }

    @ParameterizedTest(name = "S6A-AC-34 at {0} today in {1} is {2}")
    @MethodSource("datesInZones")
    @DisplayName("S6A-AC-34 today is the date of the clock's instant in the installation's zone, not in UTC")
    void todayIsTheDateInTheZone(String instant, String zone, String date) {
        Clock clock = Clock.fixed(Instant.parse(instant), ZoneId.of("UTC"));

        assertThat(InstallationZone.today(clock, ZoneId.of(zone))).isEqualTo(LocalDate.parse(date));
    }

    @ParameterizedTest(name = "S6A-AC-34 a clock in {0} gives the same today in Pacific/Kiritimati")
    @ValueSource(strings = {"UTC", "Asia/Seoul", "Pacific/Pago_Pago", "America/Los_Angeles", "Pacific/Kiritimati"})
    @DisplayName("S6A-AC-34 the clock's own zone does not matter, only its instant")
    void clockZoneDoesNotMatter(String clockZone) {
        Instant instant = Instant.parse("2026-10-15T12:30:00Z");
        Clock clock = Clock.fixed(instant, ZoneId.of(clockZone));

        assertThat(InstallationZone.today(clock, ZoneId.of("Pacific/Kiritimati")))
                .isEqualTo(LocalDate.parse("2026-10-16"));
        assertThat(InstallationZone.today(clock, ZoneId.of("Pacific/Pago_Pago")))
                .isEqualTo(LocalDate.parse("2026-10-15"));
    }

    @Test
    @DisplayName("S6A-AC-34 at UTC 12:30 the UTC date is before today in Kiritimati and after today in Pago Pago")
    void sameInstantGivesDifferentDates() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-15T12:30:00Z"), ZoneId.of("UTC"));
        LocalDate utc = InstallationZone.today(clock, InstallationZone.DEFAULT);

        assertThat(utc).isEqualTo(LocalDate.parse("2026-10-15"));
        assertThat(InstallationZone.today(clock, ZoneId.of("Pacific/Kiritimati"))).isAfter(utc);
        assertThat(InstallationZone.today(clock, ZoneId.of("Pacific/Pago_Pago"))).isEqualTo(utc);

        Clock early = Clock.fixed(Instant.parse("2026-10-15T05:30:00Z"), ZoneId.of("UTC"));
        assertThat(InstallationZone.today(early, ZoneId.of("Pacific/Pago_Pago")))
                .isBefore(InstallationZone.today(early, InstallationZone.DEFAULT));
    }
}
