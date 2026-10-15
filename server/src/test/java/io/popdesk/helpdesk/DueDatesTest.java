package io.popdesk.helpdesk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Reading a due date as sent and judging it against today (S6A-AC-31, S6A-AC-35, S6B-AC-05, S6B-AC-07).
 * Written from the Javadoc of {@link DueDates} alone.
 */
class DueDatesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

    // ---- read: missing ----

    @Test
    @DisplayName("S6B-AC-05 null is REQUIRED")
    void nullIsRequired() {
        assertThat(DueDates.read(null, TODAY)).isEqualTo(new DueDates.Read(null, "REQUIRED"));
    }

    @Test
    @DisplayName("S6B-AC-05 the empty text is REQUIRED")
    void emptyIsRequired() {
        assertThat(DueDates.read("", TODAY)).isEqualTo(new DueDates.Read(null, "REQUIRED"));
    }

    // ---- read: not YYYY-MM-DD ----

    @ParameterizedTest(name = "S6A-AC-31 \"{0}\" is INVALID_FORMAT")
    @ValueSource(strings = {
            " 2026-10-20", "2026-10-20 ", "\t2026-10-20", "2026-10-20\n",
            "2026-10-15T00:00", "2026-10-15T00:00:00Z", "2026-10-15T09:00:00+09:00",
            "2026-10-15Z", "2026-10-15+09:00", "2026-10-15 00:00",
            "20261015", "2026-1-5", "2026-01-5", "2026-1-05", "26-10-15", "02026-10-15",
            "+2026-10-15", "2026/10/20", "2026.10.20", "10-20-2026", "2026-10", "today", "abc",
            "２０２６-10-20", "2026-١٠-20"})
    @DisplayName("S6A-AC-31 anything but YYYY-MM-DD with ASCII digits is INVALID_FORMAT")
    void notTheFormIsInvalid(String text) {
        assertThat(DueDates.read(text, TODAY)).isEqualTo(new DueDates.Read(null, "INVALID_FORMAT"));
    }

    @ParameterizedTest(name = "S6A-AC-31 \"{0}\" is a day no month has")
    @ValueSource(strings = {"2026-02-30", "2026-02-29", "2027-02-29", "2026-04-31", "2026-10-32",
            "2026-13-01", "2026-00-10", "2026-10-00"})
    @DisplayName("S6A-AC-31 a day that does not exist is INVALID_FORMAT")
    void missingDayIsInvalid(String text) {
        assertThat(DueDates.read(text, TODAY)).isEqualTo(new DueDates.Read(null, "INVALID_FORMAT"));
    }

    // ---- read: against today ----

    @Test
    @DisplayName("S6B-AC-05 today itself is taken")
    void todayIsTaken() {
        assertThat(DueDates.read("2026-10-15", TODAY)).isEqualTo(new DueDates.Read(TODAY, null));
    }

    @Test
    @DisplayName("S6B-AC-05 tomorrow and later are taken")
    void laterIsTaken() {
        assertThat(DueDates.read("2026-10-16", TODAY))
                .isEqualTo(new DueDates.Read(LocalDate.of(2026, 10, 16), null));
        assertThat(DueDates.read("2030-01-31", TODAY))
                .isEqualTo(new DueDates.Read(LocalDate.of(2030, 1, 31), null));
    }

    @ParameterizedTest(name = "S6B-AC-05 \"{0}\" before 2026-10-15 is OUT_OF_RANGE")
    @ValueSource(strings = {"2026-10-14", "2026-09-30", "2025-10-15", "2000-01-01"})
    @DisplayName("S6B-AC-05 a date before today is OUT_OF_RANGE")
    void beforeTodayIsOutOfRange(String text) {
        assertThat(DueDates.read(text, TODAY)).isEqualTo(new DueDates.Read(null, "OUT_OF_RANGE"));
    }

    @ParameterizedTest(name = "S6B-AC-05 today {0}, sent {1}: date {2}, error {3}")
    @CsvSource(value = {
            "2027-01-01|2026-12-31||OUT_OF_RANGE",
            "2027-01-01|2027-01-01|2027-01-01|",
            "2026-12-31|2027-01-01|2027-01-01|",
            "2026-12-31|2026-12-30||OUT_OF_RANGE",
            "2028-02-01|2028-02-29|2028-02-29|",
            "2028-03-01|2028-02-29||OUT_OF_RANGE",
            "2028-02-29|2028-02-29|2028-02-29|"},
            delimiter = '|')
    @DisplayName("S6B-AC-05 the year's edge and the leap day are judged against today")
    void edges(LocalDate today, String text, LocalDate date, String error) {
        assertThat(DueDates.read(text, today)).isEqualTo(new DueDates.Read(date, error));
    }

    @Test
    @DisplayName("S6A-AC-35 a date that has passed is OUT_OF_RANGE even when it is the one already set")
    void passedDateSentAgainIsOutOfRange() {
        LocalDate later = LocalDate.of(2026, 10, 20);
        assertThat(DueDates.read("2026-10-15", later)).isEqualTo(new DueDates.Read(null, "OUT_OF_RANGE"));
    }

    // ---- passed ----

    @Test
    @DisplayName("S6A-AC-35 no due date has not passed")
    void noneHasNotPassed() {
        assertThat(DueDates.passed(null, TODAY)).isFalse();
    }

    @ParameterizedTest(name = "S6B-AC-07 due {0} with today 2026-10-15 passed: {1}")
    @CsvSource({
            "2026-10-14, true",
            "2025-12-31, true",
            "2000-01-01, true",
            "2026-10-15, false",
            "2026-10-16, false",
            "2027-01-01, false"})
    @DisplayName("S6B-AC-07 a due date has passed only when it is before today")
    void passedOnlyBeforeToday(LocalDate dueDate, boolean passed) {
        assertThat(DueDates.passed(dueDate, TODAY)).isEqualTo(passed);
    }

    @Test
    @DisplayName("S6A-AC-35 the last day of the year has passed on the first day of the next")
    void yearEdgePassed() {
        assertThat(DueDates.passed(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1))).isTrue();
        assertThat(DueDates.passed(LocalDate.of(2027, 1, 1), LocalDate.of(2026, 12, 31))).isFalse();
    }
}
