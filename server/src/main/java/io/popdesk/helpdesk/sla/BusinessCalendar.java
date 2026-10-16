package io.popdesk.helpdesk.sla;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.zone.ZoneOffsetTransition;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;

/**
 * The business time of one policy version (SLA-2, S8A-AC-12): one span per weekday on the wall clock of a time
 * zone, without holidays. When no weekday has a span, every day counts 24 hours; a holiday never counts. Pure: it
 * reads no clock and nothing else.
 *
 * <p>Each wall-clock span becomes two instants in the zone, so a day on which summer time starts or ends counts the
 * real time inside its span. A wall-clock time that does not exist or exists twice that day becomes an instant as
 * {@link ZonedDateTime#of} makes it: in a gap the time moves later by the gap, in an overlap the earlier offset. A run
 * of whole weeks without a holiday and without an offset change is counted at once as that many weeks of business
 * time (design.md 2절, S8A-AC-42).
 */
public final class BusinessCalendar {

    /** The minutes of a whole day, the value of a span that ends at {@code 24:00}. */
    public static final int DAY_MINUTES = 24 * 60;

    /**
     * The business hours of one weekday, in minutes of the day: {@code 0 <= opens < closes <= DAY_MINUTES}.
     */
    public record Span(int opens, int closes) {

        public Span {
            if (opens < 0 || closes > DAY_MINUTES || opens >= closes) {
                throw new IllegalArgumentException("Not a span within a day: " + opens + "-" + closes);
            }
        }
    }

    private static final Span WHOLE_DAY = new Span(0, DAY_MINUTES);

    private final ZoneId zone;
    private final Map<DayOfWeek, Span> hours;
    private final NavigableSet<LocalDate> holidays;
    private final boolean allDay;
    /** Business seconds of a week without a holiday and without an offset change. */
    private final long weekSeconds;

    /**
     * @param zone the time zone the spans and holidays are read in
     * @param hours the span of each weekday that has business hours; a weekday not in it has none. Empty makes every
     *     day 24 hours
     * @param holidays days without business time, in {@code zone}
     */
    public BusinessCalendar(ZoneId zone, Map<DayOfWeek, Span> hours, Collection<LocalDate> holidays) {
        this.zone = zone;
        this.hours = hours.isEmpty() ? Map.of() : new EnumMap<>(hours);
        this.holidays = new TreeSet<>(holidays);
        this.allDay = hours.isEmpty();
        long week = 0;
        for (DayOfWeek day : DayOfWeek.values()) {
            Span span = span(day);
            week += span == null ? 0 : (span.closes() - span.opens()) * 60L;
        }
        this.weekSeconds = week;
    }

    public ZoneId zone() {
        return zone;
    }

    /**
     * The business time between two instants.
     *
     * @param from any instant
     * @param to any instant
     * @return the whole seconds of business time in {@code [from, to)}, rounded down; 0 when {@code to} is not after
     *     {@code from}
     */
    public long seconds(Instant from, Instant to) {
        if (!to.isAfter(from)) {
            return 0;
        }
        long nanos = 0;
        LocalDate first = LocalDate.ofInstant(from, zone);
        LocalDate day = first.minusDays(1);
        LocalDate last = LocalDate.ofInstant(to, zone);
        while (!day.isAfter(last)) {
            // Only days wholly after from and before to's day may be skipped.
            long weeks = day.isAfter(first) ? wholeWeeks(day, last) : 0;
            if (weeks > 0) {
                nanos += Math.multiplyExact(weeks, weekSeconds) * 1_000_000_000L;
                day = day.plusWeeks(weeks);
                continue;
            }
            Instant[] open = open(day);
            if (open != null) {
                Instant start = open[0].isBefore(from) ? from : open[0];
                Instant end = open[1].isAfter(to) ? to : open[1];
                if (end.isAfter(start)) {
                    nanos += Duration.between(start, end).toNanos();
                }
            }
            day = day.plusDays(1);
        }
        return nanos / 1_000_000_000L;
    }

    /**
     * The instant at which a given amount of business time has passed.
     *
     * @param from any instant
     * @param seconds the business time to pass; 0 or less gives {@code from}
     * @return the earliest instant {@code t} with {@code seconds(from, t) == seconds}: inside business hours, or the
     *     end of a span when the time runs out exactly there. When {@code from} is outside business hours, counting
     *     starts at the next opening
     */
    public Instant plus(Instant from, long seconds) {
        if (seconds <= 0) {
            return from;
        }
        long left = seconds;
        LocalDate day = LocalDate.ofInstant(from, zone).minusDays(1);
        boolean started = false;
        while (true) {
            if (started && weekSeconds > 0) {
                long weeks = Math.min(wholeWeeks(day, LocalDate.MAX), (left - 1) / weekSeconds);
                if (weeks > 0) {
                    left -= weeks * weekSeconds;
                    day = day.plusWeeks(weeks);
                    continue;
                }
            }
            Instant[] open = open(day);
            if (open != null && open[1].isAfter(from)) {
                Instant start = open[0].isBefore(from) ? from : open[0];
                long available = Duration.between(start, open[1]).toSeconds();
                if (available >= left) {
                    return start.plusSeconds(left);
                }
                left -= available;
            }
            if (!day.isBefore(LocalDate.ofInstant(from, zone))) {
                started = true;
            }
            day = day.plusDays(1);
        }
    }

    /**
     * How many whole weeks from {@code day} on count {@link #weekSeconds} each and end before {@code last}: no holiday
     * and no change of the zone's offset in them. Days a week skip passes are never looked at one by one.
     */
    private long wholeWeeks(LocalDate day, LocalDate last) {
        LocalDate until = last;
        LocalDate holiday = holidays.ceiling(day);
        if (holiday != null && holiday.isBefore(until)) {
            until = holiday;
        }
        Instant dayStart = day.atStartOfDay(zone).toInstant();
        ZoneOffsetTransition transition = zone.getRules().nextTransition(dayStart.minus(Duration.ofDays(1)));
        if (transition != null) {
            // The day before the transition's day too: its span may end on the next day's midnight.
            LocalDate changes = LocalDate.ofInstant(transition.getInstant(), zone).minusDays(1);
            if (changes.isBefore(until)) {
                until = changes;
            }
        }
        if (!until.isAfter(day)) {
            return 0;
        }
        return ChronoUnit.DAYS.between(day, until) / 7;
    }

    /** The business span of a day as two instants, or null when the day has none. */
    private Instant[] open(LocalDate day) {
        if (holidays.contains(day)) {
            return null;
        }
        Span span = span(day.getDayOfWeek());
        if (span == null) {
            return null;
        }
        return new Instant[] {at(day, span.opens()), at(day, span.closes())};
    }

    private Span span(DayOfWeek day) {
        return allDay ? WHOLE_DAY : hours.get(day);
    }

    private Instant at(LocalDate day, int minutes) {
        if (minutes == DAY_MINUTES) {
            return day.plusDays(1).atStartOfDay(zone).toInstant();
        }
        return ZonedDateTime.of(day, LocalTime.of(minutes / 60, minutes % 60), zone).toInstant();
    }
}
