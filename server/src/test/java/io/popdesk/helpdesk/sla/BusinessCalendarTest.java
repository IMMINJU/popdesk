package io.popdesk.helpdesk.sla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.popdesk.helpdesk.sla.BusinessCalendar.Span;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The business time of one policy version (S8A-AC-12, S8A-AC-42; slice 8a design sections 2 and 7): starting
 * inside, outside, and on the edges of business hours, holidays, the all-day calendar, some weekdays only, a span
 * that ends at 24:00, the days summer time starts and ends in {@code America/New_York}, and a one-hour week with a
 * goal of 525,600 minutes. Written from the Javadoc of {@link BusinessCalendar} alone; the expected values are
 * worked out by hand (2026-10-12 is a Monday).
 */
class BusinessCalendarTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final long HOUR = 3600;

    /** Monday to Friday 09:00 to 18:00 in Seoul (UTC+9, no summer time): 09:00 KST is 00:00 UTC. */
    private static final BusinessCalendar WEEKDAYS = new BusinessCalendar(SEOUL, weekdays(540, 1080), Set.of());

    private static Map<DayOfWeek, Span> weekdays(int opens, int closes) {
        Map<DayOfWeek, Span> hours = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : List.of(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) {
            hours.put(day, new Span(opens, closes));
        }
        return hours;
    }

    private static Instant kst(String local) {
        return LocalDateTime.parse(local).atZone(SEOUL).toInstant();
    }

    private static Instant utc(String text) {
        return Instant.parse(text);
    }

    @Test
    @DisplayName("S8A-AC-12 a span keeps its minutes, and a whole day is 1440 minutes")
    void spanKeepsValues() {
        assertThat(BusinessCalendar.DAY_MINUTES).isEqualTo(1440);
        Span span = new Span(540, 1080);
        assertThat(span.opens()).isEqualTo(540);
        assertThat(span.closes()).isEqualTo(1080);
        assertThat(new Span(0, BusinessCalendar.DAY_MINUTES).closes()).isEqualTo(1440);
        assertThat(new Span(1439, 1440).opens()).isEqualTo(1439);
    }

    @Test
    @DisplayName("S8A-AC-12 a span outside 0 <= opens < closes <= 1440 is refused")
    void spanRefusesBadMinutes() {
        assertThatThrownBy(() -> new Span(-1, 600)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Span(600, 600)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Span(700, 600)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Span(0, 1441)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("S8A-AC-12 the calendar keeps its zone")
    void zone() {
        assertThat(WEEKDAYS.zone()).isEqualTo(SEOUL);
        assertThat(new BusinessCalendar(NEW_YORK, Map.of(), List.of()).zone()).isEqualTo(NEW_YORK);
    }

    @Test
    @DisplayName("S8A-AC-12 seconds inside one span")
    void secondsInsideSpan() {
        assertThat(WEEKDAYS.seconds(kst("2026-10-12T10:00:00"), kst("2026-10-12T11:30:00"))).isEqualTo(5400);
        assertThat(WEEKDAYS.seconds(kst("2026-10-12T09:00:00"), kst("2026-10-12T18:00:00"))).isEqualTo(9 * HOUR);
        assertThat(WEEKDAYS.seconds(kst("2026-10-12T10:00:00"), kst("2026-10-12T10:00:01"))).isEqualTo(1);
    }

    @Test
    @DisplayName("S8A-AC-12 seconds leave out the night and the weekend")
    void secondsSkipClosedTime() {
        // Monday 17:00 to 18:00, then Tuesday 09:00 to 10:00
        assertThat(WEEKDAYS.seconds(kst("2026-10-12T17:00:00"), kst("2026-10-13T10:00:00"))).isEqualTo(2 * HOUR);
        // Friday 17:00 to 18:00, then Monday 09:00 to 10:00
        assertThat(WEEKDAYS.seconds(kst("2026-10-16T17:00:00"), kst("2026-10-19T10:00:00"))).isEqualTo(2 * HOUR);
        // a whole week from Monday midnight: five days of nine hours
        assertThat(WEEKDAYS.seconds(kst("2026-10-12T00:00:00"), kst("2026-10-19T00:00:00"))).isEqualTo(45 * HOUR);
        // three whole weeks and a bit: Monday 10:00 to Monday 10:00 three weeks on is 3 * 45 hours
        assertThat(WEEKDAYS.seconds(kst("2026-10-12T10:00:00"), kst("2026-11-02T10:00:00"))).isEqualTo(135 * HOUR);
        // all inside the weekend
        assertThat(WEEKDAYS.seconds(kst("2026-10-17T08:00:00"), kst("2026-10-18T20:00:00"))).isZero();
    }

    @Test
    @DisplayName("S8A-AC-12 seconds from outside business hours count from the next opening")
    void secondsFromOutside() {
        assertThat(WEEKDAYS.seconds(kst("2026-10-12T07:00:00"), kst("2026-10-12T10:00:00"))).isEqualTo(HOUR);
        assertThat(WEEKDAYS.seconds(kst("2026-10-12T20:00:00"), kst("2026-10-13T09:30:00"))).isEqualTo(1800);
        assertThat(WEEKDAYS.seconds(kst("2026-10-17T12:00:00"), kst("2026-10-19T09:00:01"))).isEqualTo(1);
    }

    @Test
    @DisplayName("S8A-AC-12 seconds on the edges: from the opening, from the closing, to the closing")
    void secondsOnEdges() {
        assertThat(WEEKDAYS.seconds(kst("2026-10-12T09:00:00"), kst("2026-10-12T09:00:01"))).isEqualTo(1);
        assertThat(WEEKDAYS.seconds(kst("2026-10-12T18:00:00"), kst("2026-10-13T09:00:00"))).isZero();
        assertThat(WEEKDAYS.seconds(kst("2026-10-12T18:00:00"), kst("2026-10-13T09:00:01"))).isEqualTo(1);
        assertThat(WEEKDAYS.seconds(kst("2026-10-12T17:59:59"), kst("2026-10-12T18:00:00"))).isEqualTo(1);
        assertThat(WEEKDAYS.seconds(kst("2026-10-12T17:59:59"), kst("2026-10-12T23:00:00"))).isEqualTo(1);
        assertThat(WEEKDAYS.seconds(kst("2026-10-12T08:59:59"), kst("2026-10-12T09:00:00"))).isZero();
    }

    @Test
    @DisplayName("S8A-AC-12 seconds are 0 when to is not after from")
    void secondsEmptyRange() {
        Instant at = kst("2026-10-12T10:00:00");
        assertThat(WEEKDAYS.seconds(at, at)).isZero();
        assertThat(WEEKDAYS.seconds(at, kst("2026-10-12T09:30:00"))).isZero();
        assertThat(WEEKDAYS.seconds(kst("2026-10-19T10:00:00"), at)).isZero();
        BusinessCalendar allDay = new BusinessCalendar(ZoneOffset.UTC, Map.of(), List.of());
        assertThat(allDay.seconds(at, at)).isZero();
        assertThat(allDay.seconds(at, at.minusSeconds(1))).isZero();
    }

    @Test
    @DisplayName("S8A-AC-12 seconds are whole seconds rounded down")
    void secondsRoundDown() {
        Instant at = kst("2026-10-12T10:00:00");
        assertThat(WEEKDAYS.seconds(at, at.plusMillis(999))).isZero();
        assertThat(WEEKDAYS.seconds(at.plusMillis(500), at.plusSeconds(2))).isEqualTo(1);
        assertThat(WEEKDAYS.seconds(at, at.plusSeconds(2).minusNanos(1))).isEqualTo(1);
    }

    @Test
    @DisplayName("S8A-AC-12 plus inside business hours")
    void plusInside() {
        assertThat(WEEKDAYS.plus(kst("2026-10-12T10:00:00"), HOUR)).isEqualTo(kst("2026-10-12T11:00:00"));
        assertThat(WEEKDAYS.plus(kst("2026-10-12T09:00:00"), 1)).isEqualTo(kst("2026-10-12T09:00:01"));
        // Monday 17:00 + 2 hours: one hour on Monday, one from Tuesday 09:00
        assertThat(WEEKDAYS.plus(kst("2026-10-12T17:00:00"), 2 * HOUR)).isEqualTo(kst("2026-10-13T10:00:00"));
        // Friday 17:00 + 2 hours: Monday 10:00
        assertThat(WEEKDAYS.plus(kst("2026-10-16T17:00:00"), 2 * HOUR)).isEqualTo(kst("2026-10-19T10:00:00"));
    }

    @Test
    @DisplayName("S8A-AC-12 plus that runs out exactly at the closing gives the closing")
    void plusEndsAtClosing() {
        assertThat(WEEKDAYS.plus(kst("2026-10-12T17:00:00"), HOUR)).isEqualTo(kst("2026-10-12T18:00:00"));
        assertThat(WEEKDAYS.plus(kst("2026-10-12T09:00:00"), 9 * HOUR)).isEqualTo(kst("2026-10-12T18:00:00"));
        // a whole week from Monday's opening ends at Friday's closing
        assertThat(WEEKDAYS.plus(kst("2026-10-12T09:00:00"), 45 * HOUR)).isEqualTo(kst("2026-10-16T18:00:00"));
        // three whole weeks end at the Friday closing of the third week
        assertThat(WEEKDAYS.plus(kst("2026-10-12T09:00:00"), 135 * HOUR)).isEqualTo(kst("2026-10-30T18:00:00"));
        // one second more is one second past the next opening
        assertThat(WEEKDAYS.plus(kst("2026-10-12T17:00:00"), HOUR + 1)).isEqualTo(kst("2026-10-13T09:00:01"));
        assertThat(WEEKDAYS.plus(kst("2026-10-12T09:00:00"), 45 * HOUR + 1)).isEqualTo(kst("2026-10-19T09:00:01"));
    }

    @Test
    @DisplayName("S8A-AC-12 plus from outside business hours counts from the next opening")
    void plusFromOutside() {
        assertThat(WEEKDAYS.plus(kst("2026-10-12T07:00:00"), 60)).isEqualTo(kst("2026-10-12T09:01:00"));
        assertThat(WEEKDAYS.plus(kst("2026-10-12T18:00:00"), 1)).isEqualTo(kst("2026-10-13T09:00:01"));
        assertThat(WEEKDAYS.plus(kst("2026-10-12T22:30:00"), 1800)).isEqualTo(kst("2026-10-13T09:30:00"));
        assertThat(WEEKDAYS.plus(kst("2026-10-17T12:00:00"), HOUR)).isEqualTo(kst("2026-10-19T10:00:00"));
        // a whole week from Saturday ends at the next Friday's closing
        assertThat(WEEKDAYS.plus(kst("2026-10-17T12:00:00"), 45 * HOUR)).isEqualTo(kst("2026-10-23T18:00:00"));
    }

    @Test
    @DisplayName("S8A-AC-12 plus of 0 or less gives from, also outside business hours")
    void plusNothing() {
        Instant inside = kst("2026-10-12T10:00:00");
        Instant outside = kst("2026-10-12T07:00:00");
        Instant odd = kst("2026-10-17T12:00:00").plusNanos(123_456_789);
        assertThat(WEEKDAYS.plus(inside, 0)).isEqualTo(inside);
        assertThat(WEEKDAYS.plus(outside, 0)).isEqualTo(outside);
        assertThat(WEEKDAYS.plus(odd, 0)).isEqualTo(odd);
        assertThat(WEEKDAYS.plus(inside, -1)).isEqualTo(inside);
        assertThat(WEEKDAYS.plus(outside, -3600)).isEqualTo(outside);
        assertThat(WEEKDAYS.plus(outside, Long.MIN_VALUE)).isEqualTo(outside);
    }

    @Test
    @DisplayName("S8A-AC-12 plus keeps the part of a second from inside business hours")
    void plusKeepsFraction() {
        Instant from = kst("2026-10-12T10:00:00").plusMillis(500);
        assertThat(WEEKDAYS.plus(from, 1)).isEqualTo(kst("2026-10-12T10:00:01").plusMillis(500));
    }

    @Test
    @DisplayName("S8A-AC-12 a holiday counts nothing, also on a weekday with hours")
    void holiday() {
        // Tuesday 2026-10-13 is a holiday
        BusinessCalendar calendar = new BusinessCalendar(SEOUL, weekdays(540, 1080), List.of(LocalDate.of(2026, 10, 13)));
        assertThat(calendar.seconds(kst("2026-10-12T17:00:00"), kst("2026-10-14T10:00:00"))).isEqualTo(2 * HOUR);
        assertThat(calendar.seconds(kst("2026-10-13T09:00:00"), kst("2026-10-13T18:00:00"))).isZero();
        assertThat(calendar.plus(kst("2026-10-12T17:00:00"), 2 * HOUR)).isEqualTo(kst("2026-10-14T10:00:00"));
        assertThat(calendar.plus(kst("2026-10-13T10:00:00"), 60)).isEqualTo(kst("2026-10-14T09:01:00"));
        // the week holding it has four days of nine hours
        assertThat(calendar.seconds(kst("2026-10-12T00:00:00"), kst("2026-10-19T00:00:00"))).isEqualTo(36 * HOUR);
        assertThat(calendar.plus(kst("2026-10-12T09:00:00"), 36 * HOUR)).isEqualTo(kst("2026-10-16T18:00:00"));
        // a holiday on a Saturday changes nothing
        BusinessCalendar saturday = new BusinessCalendar(SEOUL, weekdays(540, 1080), List.of(LocalDate.of(2026, 10, 17)));
        assertThat(saturday.seconds(kst("2026-10-12T00:00:00"), kst("2026-10-19T00:00:00"))).isEqualTo(45 * HOUR);
    }

    @Test
    @DisplayName("S8A-AC-12 a holiday several weeks on is counted after whole weeks are passed")
    void holidayAfterWholeWeeks() {
        // Wednesday 2026-11-04 is a holiday, three weeks and two days after Monday 2026-10-12
        BusinessCalendar calendar = new BusinessCalendar(SEOUL, weekdays(540, 1080), List.of(LocalDate.of(2026, 11, 4)));
        // three weeks (135 h) up to Monday 2026-11-02, then Monday and Tuesday (18 h), Wednesday is off
        assertThat(calendar.seconds(kst("2026-10-12T09:00:00"), kst("2026-11-05T09:00:00"))).isEqualTo(153 * HOUR);
        assertThat(calendar.plus(kst("2026-10-12T09:00:00"), 153 * HOUR)).isEqualTo(kst("2026-11-03T18:00:00"));
        assertThat(calendar.plus(kst("2026-10-12T09:00:00"), 153 * HOUR + 1)).isEqualTo(kst("2026-11-05T09:00:01"));
        // five weeks from Monday: one day less
        assertThat(calendar.seconds(kst("2026-10-12T00:00:00"), kst("2026-11-16T00:00:00"))).isEqualTo(216 * HOUR);
    }

    @Test
    @DisplayName("S8A-AC-12 holidays are read in the calendar's zone")
    void holidayInZone() {
        // in New York 2026-10-13 runs from 04:00 UTC to 04:00 UTC the next day (EDT, UTC-4)
        BusinessCalendar calendar = new BusinessCalendar(NEW_YORK, Map.of(), List.of(LocalDate.of(2026, 10, 13)));
        assertThat(calendar.seconds(utc("2026-10-13T00:00:00Z"), utc("2026-10-14T12:00:00Z"))).isEqualTo(12 * HOUR);
        assertThat(calendar.plus(utc("2026-10-13T00:00:00Z"), 4 * HOUR)).isEqualTo(utc("2026-10-13T04:00:00Z"));
        assertThat(calendar.plus(utc("2026-10-13T00:00:00Z"), 4 * HOUR + 1)).isEqualTo(utc("2026-10-14T04:00:01Z"));
    }

    @Test
    @DisplayName("S8A-AC-12 with no weekday hours every day counts 24 hours")
    void allDay() {
        BusinessCalendar calendar = new BusinessCalendar(ZoneOffset.UTC, Map.of(), List.of());
        Instant from = utc("2026-10-12T13:17:05Z");
        assertThat(calendar.seconds(from, utc("2026-10-12T13:17:06Z"))).isEqualTo(1);
        assertThat(calendar.seconds(from, utc("2026-10-19T13:17:05Z"))).isEqualTo(7 * 24 * HOUR);
        // 2026-10-12 to 2027-01-12 is 31 + 30 + 31 = 92 days
        assertThat(calendar.seconds(from, utc("2027-01-12T13:17:05Z"))).isEqualTo(92 * 24 * HOUR);
        assertThat(calendar.plus(from, 1)).isEqualTo(utc("2026-10-12T13:17:06Z"));
        assertThat(calendar.plus(from, 10 * 24 * HOUR + 7)).isEqualTo(utc("2026-10-22T13:17:12Z"));
        assertThat(calendar.plus(utc("2026-10-17T23:59:59Z"), 2)).isEqualTo(utc("2026-10-18T00:00:01Z"));
    }

    @Test
    @DisplayName("S8A-AC-12 with no weekday hours a holiday still counts nothing")
    void allDayHoliday() {
        BusinessCalendar calendar = new BusinessCalendar(SEOUL, Map.of(), List.of(LocalDate.of(2026, 10, 13)));
        // Monday 12:00 to Wednesday 12:00: 12 hours on Monday, none on Tuesday, 12 on Wednesday
        assertThat(calendar.seconds(kst("2026-10-12T12:00:00"), kst("2026-10-14T12:00:00"))).isEqualTo(24 * HOUR);
        // a whole Monday runs out at Tuesday 00:00, the end of Monday
        assertThat(calendar.plus(kst("2026-10-12T00:00:00"), 24 * HOUR)).isEqualTo(kst("2026-10-13T00:00:00"));
        assertThat(calendar.plus(kst("2026-10-12T00:00:00"), 24 * HOUR + 1)).isEqualTo(kst("2026-10-14T00:00:01"));
        assertThat(calendar.plus(kst("2026-10-13T08:00:00"), 60)).isEqualTo(kst("2026-10-14T00:01:00"));
        assertThat(calendar.seconds(kst("2026-10-12T00:00:00"), kst("2026-10-19T00:00:00"))).isEqualTo(6 * 24 * HOUR);
    }

    @Test
    @DisplayName("S8A-AC-12 only some weekdays have hours, one of them up to 24:00")
    void someWeekdays() {
        // Monday 09:00 to 18:00 and Wednesday 13:00 to 24:00
        Map<DayOfWeek, Span> hours = new EnumMap<>(DayOfWeek.class);
        hours.put(DayOfWeek.MONDAY, new Span(540, 1080));
        hours.put(DayOfWeek.WEDNESDAY, new Span(780, BusinessCalendar.DAY_MINUTES));
        BusinessCalendar calendar = new BusinessCalendar(SEOUL, hours, List.of());
        assertThat(calendar.seconds(kst("2026-10-12T00:00:00"), kst("2026-10-19T00:00:00"))).isEqualTo(20 * HOUR);
        assertThat(calendar.seconds(kst("2026-10-14T23:00:00"), kst("2026-10-15T10:00:00"))).isEqualTo(HOUR);
        assertThat(calendar.seconds(kst("2026-10-13T10:00:00"), kst("2026-10-14T14:00:00"))).isEqualTo(HOUR);
        // Wednesday 23:00 + 1 hour ends at Thursday 00:00, the 24:00 of Wednesday
        assertThat(calendar.plus(kst("2026-10-14T23:00:00"), HOUR)).isEqualTo(kst("2026-10-15T00:00:00"));
        // one second more goes on to the next Monday
        assertThat(calendar.plus(kst("2026-10-14T23:00:00"), HOUR + 1)).isEqualTo(kst("2026-10-19T09:00:01"));
        // Monday 17:00 + 2 hours: Monday 17:00 to 18:00, then Wednesday 13:00 to 14:00
        assertThat(calendar.plus(kst("2026-10-12T17:00:00"), 2 * HOUR)).isEqualTo(kst("2026-10-14T14:00:00"));
        // from Tuesday: Wednesday's opening
        assertThat(calendar.plus(kst("2026-10-13T10:00:00"), 60)).isEqualTo(kst("2026-10-14T13:01:00"));
        // two whole weeks from Monday's opening end at the second Wednesday's 24:00
        assertThat(calendar.plus(kst("2026-10-12T09:00:00"), 40 * HOUR)).isEqualTo(kst("2026-10-22T00:00:00"));
    }

    @Test
    @DisplayName("S8A-AC-12 two days up to 24:00 and from 00:00 join across midnight")
    void acrossMidnight() {
        Map<DayOfWeek, Span> hours = new EnumMap<>(DayOfWeek.class);
        hours.put(DayOfWeek.TUESDAY, new Span(0, BusinessCalendar.DAY_MINUTES));
        hours.put(DayOfWeek.WEDNESDAY, new Span(0, BusinessCalendar.DAY_MINUTES));
        BusinessCalendar calendar = new BusinessCalendar(SEOUL, hours, List.of());
        assertThat(calendar.seconds(kst("2026-10-13T23:00:00"), kst("2026-10-14T01:00:00"))).isEqualTo(2 * HOUR);
        assertThat(calendar.plus(kst("2026-10-13T23:00:00"), 2 * HOUR)).isEqualTo(kst("2026-10-14T01:00:00"));
        assertThat(calendar.plus(kst("2026-10-13T23:00:00"), HOUR)).isEqualTo(kst("2026-10-14T00:00:00"));
        assertThat(calendar.seconds(kst("2026-10-12T00:00:00"), kst("2026-10-26T00:00:00"))).isEqualTo(96 * HOUR);
        assertThat(calendar.plus(kst("2026-10-12T05:00:00"), 48 * HOUR)).isEqualTo(kst("2026-10-15T00:00:00"));
    }

    @Test
    @DisplayName("S8A-AC-12 the Sunday summer time starts in New York has 23 real hours")
    void summerTimeStarts() {
        // 2026-03-08: 02:00 EST becomes 03:00 EDT. 00:00 EST is 05:00 UTC, the next midnight (EDT) 04:00 UTC
        BusinessCalendar sunday =
                new BusinessCalendar(NEW_YORK, Map.of(DayOfWeek.SUNDAY, new Span(0, BusinessCalendar.DAY_MINUTES)), List.of());
        assertThat(sunday.seconds(utc("2026-03-07T00:00:00Z"), utc("2026-03-10T00:00:00Z"))).isEqualTo(23 * HOUR);
        assertThat(sunday.plus(utc("2026-03-08T05:00:00Z"), 23 * HOUR)).isEqualTo(utc("2026-03-09T04:00:00Z"));
        // the next Sunday starts at 04:00 UTC (EDT)
        assertThat(sunday.plus(utc("2026-03-08T05:00:00Z"), 23 * HOUR + 1)).isEqualTo(utc("2026-03-15T04:00:01Z"));
        // the Sunday before has 24 hours
        assertThat(sunday.seconds(utc("2026-02-28T00:00:00Z"), utc("2026-03-03T00:00:00Z"))).isEqualTo(24 * HOUR);

        // 01:00 to 04:00 on that day: 01:00 EST (06:00 UTC) to 04:00 EDT (08:00 UTC), 2 real hours
        BusinessCalendar early = new BusinessCalendar(NEW_YORK, Map.of(DayOfWeek.SUNDAY, new Span(60, 240)), List.of());
        assertThat(early.seconds(utc("2026-03-08T00:00:00Z"), utc("2026-03-09T00:00:00Z"))).isEqualTo(2 * HOUR);
        assertThat(early.plus(utc("2026-03-08T00:00:00Z"), HOUR)).isEqualTo(utc("2026-03-08T07:00:00Z"));
        assertThat(early.plus(utc("2026-03-08T00:00:00Z"), 2 * HOUR)).isEqualTo(utc("2026-03-08T08:00:00Z"));
        assertThat(early.seconds(utc("2026-03-01T00:00:00Z"), utc("2026-03-02T00:00:00Z"))).isEqualTo(3 * HOUR);

        // 02:30 to 05:00: 02:30 does not exist and moves on by the hour to 03:30 EDT (07:30 UTC); 05:00 EDT is 09:00 UTC
        BusinessCalendar gap = new BusinessCalendar(NEW_YORK, Map.of(DayOfWeek.SUNDAY, new Span(150, 300)), List.of());
        assertThat(gap.seconds(utc("2026-03-08T00:00:00Z"), utc("2026-03-09T00:00:00Z"))).isEqualTo(5400);
        assertThat(gap.plus(utc("2026-03-08T00:00:00Z"), 60)).isEqualTo(utc("2026-03-08T07:31:00Z"));
        assertThat(gap.plus(utc("2026-03-08T00:00:00Z"), 5400)).isEqualTo(utc("2026-03-08T09:00:00Z"));
    }

    @Test
    @DisplayName("S8A-AC-12 the Sunday summer time ends in New York has 25 real hours")
    void summerTimeEnds() {
        // 2026-11-01: 02:00 EDT becomes 01:00 EST. 00:00 EDT is 04:00 UTC, the next midnight (EST) 05:00 UTC
        BusinessCalendar sunday =
                new BusinessCalendar(NEW_YORK, Map.of(DayOfWeek.SUNDAY, new Span(0, BusinessCalendar.DAY_MINUTES)), List.of());
        assertThat(sunday.seconds(utc("2026-10-31T00:00:00Z"), utc("2026-11-03T00:00:00Z"))).isEqualTo(25 * HOUR);
        assertThat(sunday.plus(utc("2026-11-01T04:00:00Z"), 25 * HOUR)).isEqualTo(utc("2026-11-02T05:00:00Z"));
        assertThat(sunday.plus(utc("2026-11-01T04:00:00Z"), 25 * HOUR + 1)).isEqualTo(utc("2026-11-08T05:00:01Z"));

        // 01:00 to 04:00: 01:00 exists twice and takes the earlier offset, 01:00 EDT (05:00 UTC); 04:00 EST is 09:00 UTC
        BusinessCalendar early = new BusinessCalendar(NEW_YORK, Map.of(DayOfWeek.SUNDAY, new Span(60, 240)), List.of());
        assertThat(early.seconds(utc("2026-11-01T00:00:00Z"), utc("2026-11-02T00:00:00Z"))).isEqualTo(4 * HOUR);
        assertThat(early.plus(utc("2026-11-01T00:00:00Z"), 4 * HOUR)).isEqualTo(utc("2026-11-01T09:00:00Z"));
        assertThat(early.plus(utc("2026-11-01T00:00:00Z"), 1)).isEqualTo(utc("2026-11-01T05:00:01Z"));

        // 01:30 to 03:00: 01:30 EDT (05:30 UTC) to 03:00 EST (08:00 UTC), 2.5 real hours
        BusinessCalendar overlap = new BusinessCalendar(NEW_YORK, Map.of(DayOfWeek.SUNDAY, new Span(90, 180)), List.of());
        assertThat(overlap.seconds(utc("2026-11-01T00:00:00Z"), utc("2026-11-02T00:00:00Z"))).isEqualTo(9000);
        assertThat(overlap.seconds(utc("2026-11-08T00:00:00Z"), utc("2026-11-09T00:00:00Z"))).isEqualTo(5400);
    }

    @Test
    @DisplayName("S8A-AC-12 whole weeks across a change of offset still end on the wall clock")
    void weeksAcrossOffsetChange() {
        // Monday to Friday 09:00 to 17:00 in New York, 40 hours a week
        BusinessCalendar calendar = new BusinessCalendar(NEW_YORK, weekdays(540, 1020), List.of());
        // Monday 2026-03-02 09:00 EST (14:00 UTC) + two weeks ends Friday 2026-03-13 17:00 EDT (21:00 UTC)
        assertThat(calendar.plus(utc("2026-03-02T14:00:00Z"), 80 * HOUR)).isEqualTo(utc("2026-03-13T21:00:00Z"));
        assertThat(calendar.seconds(utc("2026-03-02T00:00:00Z"), utc("2026-03-16T00:00:00Z"))).isEqualTo(80 * HOUR);
        // Monday 2026-10-26 09:00 EDT (13:00 UTC) + two weeks ends Friday 2026-11-06 17:00 EST (22:00 UTC)
        assertThat(calendar.plus(utc("2026-10-26T13:00:00Z"), 80 * HOUR)).isEqualTo(utc("2026-11-06T22:00:00Z"));
        assertThat(calendar.plus(utc("2026-10-26T13:00:00Z"), 80 * HOUR + 1)).isEqualTo(utc("2026-11-09T14:00:01Z"));
        // all day in New York over the spring Sunday counts the real time
        BusinessCalendar allDay = new BusinessCalendar(NEW_YORK, Map.of(), List.of());
        assertThat(allDay.seconds(utc("2026-03-07T12:00:00Z"), utc("2026-03-09T12:00:00Z"))).isEqualTo(48 * HOUR);
        assertThat(allDay.plus(utc("2026-03-07T12:00:00Z"), 48 * HOUR)).isEqualTo(utc("2026-03-09T12:00:00Z"));
        // a year all day: 2026-01-01 to 2027-01-01 is 365 days of real time
        assertThat(allDay.seconds(utc("2026-01-01T05:00:00Z"), utc("2027-01-01T05:00:00Z"))).isEqualTo(365 * 24 * HOUR);
        assertThat(allDay.plus(utc("2026-01-01T05:00:00Z"), 365 * 24 * HOUR)).isEqualTo(utc("2027-01-01T05:00:00Z"));
    }

    @Test
    @DisplayName("S8A-AC-12 plus and seconds undo each other, and plus gives the earliest such instant")
    void plusAndSecondsInverse() {
        Map<DayOfWeek, Span> some = new EnumMap<>(DayOfWeek.class);
        some.put(DayOfWeek.MONDAY, new Span(540, 1080));
        some.put(DayOfWeek.WEDNESDAY, new Span(780, BusinessCalendar.DAY_MINUTES));
        List<BusinessCalendar> calendars = List.of(
                WEEKDAYS,
                new BusinessCalendar(SEOUL, some, List.of(LocalDate.of(2026, 10, 21), LocalDate.of(2026, 11, 2))),
                new BusinessCalendar(NEW_YORK, weekdays(540, 1020), List.of(LocalDate.of(2026, 11, 3))),
                new BusinessCalendar(NEW_YORK, Map.of(), List.of(LocalDate.of(2026, 11, 1))));
        List<Instant> froms = List.of(
                utc("2026-10-12T00:00:00Z"),
                utc("2026-10-14T05:30:00Z"),
                utc("2026-10-17T23:00:00Z"),
                utc("2026-10-27T13:00:00Z"));
        long[] amounts = {1, 59, 3600, 7 * HOUR + 13, 45 * HOUR, 200 * HOUR + 1, 1000 * HOUR};
        for (BusinessCalendar calendar : calendars) {
            for (Instant from : froms) {
                for (long amount : amounts) {
                    Instant t = calendar.plus(from, amount);
                    assertThat(calendar.seconds(from, t)).as("%s %s %d", calendar.zone(), from, amount).isEqualTo(amount);
                    assertThat(calendar.seconds(from, t.minusNanos(1)))
                            .as("earliest %s %s %d", calendar.zone(), from, amount)
                            .isEqualTo(amount - 1);
                }
            }
        }
    }

    @Test
    @DisplayName("S8A-AC-42 one hour a week and a goal of 525,600 minutes: 8,760 weeks")
    void oneHourWeekLongGoal() {
        BusinessCalendar calendar = oneHourMondays(List.of());
        long goal = 525_600L * 60;
        Instant from = utc("2026-10-12T09:00:00Z");
        // 525,600 minutes are 8,760 hours; the last one ends at Monday 10:00 of week 8,759 after the first
        Instant expected = utc("2026-10-12T10:00:00Z").plus(Duration.ofDays(7L * 8759));
        assertThat(calendar.plus(from, goal)).isEqualTo(expected);
        assertThat(calendar.seconds(from, expected)).isEqualTo(goal);
        assertThat(calendar.seconds(from, expected.minusSeconds(1))).isEqualTo(goal - 1);
    }

    @Test
    @DisplayName("S8A-AC-42 one hour a week, 500 holidays, and a goal of 525,600 minutes, worked out by hand")
    void oneHourWeekWithHolidays() {
        // 500 days from Friday 2027-01-01 to Sunday 2028-05-14 hold 71 Mondays (2027-01-04 to 2028-05-08)
        BusinessCalendar calendar = oneHourMondays(fiveHundredDays());
        long goal = 525_600L * 60;
        Instant from = utc("2026-10-12T09:00:00Z");
        Instant expected = utc("2026-10-12T10:00:00Z").plus(Duration.ofDays(7L * (8759 + 71)));
        assertThat(calendar.plus(from, goal)).isEqualTo(expected);
        assertThat(calendar.seconds(from, expected)).isEqualTo(goal);
        // a holiday Monday counts nothing
        assertThat(calendar.seconds(utc("2027-01-04T00:00:00Z"), utc("2027-01-05T00:00:00Z"))).isZero();
        assertThat(calendar.seconds(utc("2028-05-15T00:00:00Z"), utc("2028-05-16T00:00:00Z"))).isEqualTo(HOUR);
    }

    @Test
    @DisplayName("S8A-AC-42 that plus takes under 5 ms (median of several runs after a warm-up)")
    void oneHourWeekIsFast() {
        BusinessCalendar calendar = oneHourMondays(fiveHundredDays());
        long goal = 525_600L * 60;
        Instant from = utc("2026-10-12T09:00:00Z");
        Instant expected = utc("2026-10-12T10:00:00Z").plus(Duration.ofDays(7L * (8759 + 71)));
        for (int i = 0; i < 30; i++) {
            assertThat(calendar.plus(from, goal)).isEqualTo(expected);
        }
        long[] nanos = new long[21];
        for (int i = 0; i < nanos.length; i++) {
            long start = System.nanoTime();
            Instant result = calendar.plus(from, goal);
            nanos[i] = System.nanoTime() - start;
            assertThat(result).isEqualTo(expected);
        }
        Arrays.sort(nanos);
        long median = nanos[nanos.length / 2];
        System.out.printf(
                "S8A-AC-42 plus(525,600 min, 1 h/week, 500 holidays): median %.3f ms, min %.3f ms, max %.3f ms%n",
                median / 1e6, nanos[0] / 1e6, nanos[nanos.length - 1] / 1e6);
        assertThat(median).isLessThan(Duration.ofMillis(5).toNanos());
    }

    private static BusinessCalendar oneHourMondays(List<LocalDate> holidays) {
        return new BusinessCalendar(ZoneOffset.UTC, Map.of(DayOfWeek.MONDAY, new Span(540, 600)), holidays);
    }

    private static List<LocalDate> fiveHundredDays() {
        List<LocalDate> days = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            days.add(LocalDate.of(2027, 1, 1).plusDays(i));
        }
        return days;
    }
}
