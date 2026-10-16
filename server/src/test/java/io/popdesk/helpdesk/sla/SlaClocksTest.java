package io.popdesk.helpdesk.sla;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.helpdesk.sla.BusinessCalendar.Span;
import io.popdesk.helpdesk.sla.SlaClocks.Clock;
import io.popdesk.helpdesk.sla.SlaClocks.Outcome;
import io.popdesk.helpdesk.sla.SlaClocks.State;
import io.popdesk.helpdesk.sla.SlaClocks.Stretch;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The calculation of one SLA goal's clock (S8A-AC-13 to S8A-AC-20; slice 8a design sections 0, 2 and 7): bringing a
 * clock over three later versions, stopping and settling, the trash and the restore, reopening, the warning and due
 * times, the state on the edges of both, and the minutes left. Written from the Javadoc of {@link SlaClocks} alone;
 * the expected values are worked out by hand (2026-10-12 is a Monday).
 */
class SlaClocksTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** Every day 24 hours in UTC: business time is plain elapsed time. */
    private static final BusinessCalendar ALL_DAY = new BusinessCalendar(ZoneOffset.UTC, Map.of(), List.of());

    /** Monday to Friday 09:00 to 18:00 in Seoul: 09:00 KST is 00:00 UTC, 18:00 KST is 09:00 UTC. */
    private static final BusinessCalendar WEEKDAYS = new BusinessCalendar(SEOUL, weekdays(), List.of());

    /** Thursdays 00:00 to 01:00 UTC only. */
    private static final BusinessCalendar THURSDAY_HOUR =
            new BusinessCalendar(ZoneOffset.UTC, Map.of(DayOfWeek.THURSDAY, new Span(0, 60)), List.of());

    private static final Instant T0 = Instant.parse("2026-10-12T00:00:00Z");
    private static final Instant WARN = Instant.parse("2026-10-20T05:00:00Z");
    private static final Instant DUE = Instant.parse("2026-10-21T05:00:00Z");

    private static Map<DayOfWeek, Span> weekdays() {
        Map<DayOfWeek, Span> hours = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : List.of(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) {
            hours.put(day, new Span(540, 1080));
        }
        return hours;
    }

    private static Instant kst(String local) {
        return LocalDateTime.parse(local).atZone(SEOUL).toInstant();
    }

    private static Instant utc(String text) {
        return Instant.parse(text);
    }

    private static Instant at(long seconds) {
        return T0.plusSeconds(seconds);
    }

    // ---- Clock ----

    @Test
    @DisplayName("design 0절 1 a clock that starts now runs with nothing counted and no due times")
    void startingAt() {
        Clock clock = Clock.startingAt(T0);
        assertThat(clock).isEqualTo(new Clock(0, T0, null, null, null));
        assertThat(clock.running()).isTrue();
        assertThat(new Clock(10, null, null, null, null).running()).isFalse();
        assertThat(new Clock(10, null, Outcome.MET, WARN, DUE).running()).isFalse();
    }

    // ---- catchUp ----

    @Test
    @DisplayName("S8A-AC-20 a running clock passes three later versions, each stretch counted with the version before")
    void catchUpThreeVersions() {
        List<Stretch> versions = List.of(
                new Stretch(utc("2026-10-01T00:00:00Z"), WEEKDAYS),
                new Stretch(utc("2026-10-14T00:00:00Z"), ALL_DAY),
                new Stretch(utc("2026-10-15T00:00:00Z"), THURSDAY_HOUR),
                new Stretch(utc("2026-10-16T00:00:00Z"), WEEKDAYS));
        Clock clock = new Clock(100, T0, null, WARN, DUE);
        // Monday 00:00 UTC (09:00 KST) to Wednesday 00:00 UTC with the weekday calendar: Monday and Tuesday, 9 h each
        // then Wednesday 00:00 to Thursday 00:00 all day: 24 h
        // then Thursday 00:00 to Friday 00:00 with the Thursday hour: 1 h
        long expected = 100 + 18 * 3600 + 24 * 3600 + 3600;
        Clock caught = SlaClocks.catchUp(clock, versions);
        assertThat(caught).isEqualTo(new Clock(expected, utc("2026-10-16T00:00:00Z"), null, WARN, DUE));
    }

    @Test
    @DisplayName("S8A-AC-20 a clock that started after a version began counts only from its own start")
    void catchUpLateClock() {
        List<Stretch> versions = List.of(
                new Stretch(utc("2026-10-01T00:00:00Z"), WEEKDAYS),
                new Stretch(utc("2026-10-14T00:00:00Z"), ALL_DAY),
                new Stretch(utc("2026-10-15T00:00:00Z"), THURSDAY_HOUR),
                new Stretch(utc("2026-10-16T00:00:00Z"), WEEKDAYS));
        // running since Wednesday 12:00 UTC: nothing before the second version's start
        // Wednesday 12:00 to Thursday 00:00 all day: 12 h; Thursday with the Thursday hour: 1 h
        Clock wednesday = new Clock(7, utc("2026-10-14T12:00:00Z"), null, null, null);
        assertThat(SlaClocks.catchUp(wednesday, versions))
                .isEqualTo(new Clock(7 + 12 * 3600 + 3600, utc("2026-10-16T00:00:00Z"), null, null, null));
        // running since Thursday 00:30: half of the Thursday hour
        Clock thursday = new Clock(0, utc("2026-10-15T00:30:00Z"), null, WARN, DUE);
        assertThat(SlaClocks.catchUp(thursday, versions))
                .isEqualTo(new Clock(1800, utc("2026-10-16T00:00:00Z"), null, WARN, DUE));
        // running since after the last version began: nothing changes
        Clock friday = new Clock(55, utc("2026-10-16T06:00:00Z"), null, WARN, DUE);
        assertThat(SlaClocks.catchUp(friday, versions)).isEqualTo(friday);
    }

    @Test
    @DisplayName("S8A-AC-20 a clock started exactly when a version began moves nothing for that version")
    void catchUpAtVersionStart() {
        List<Stretch> versions = List.of(
                new Stretch(utc("2026-10-01T00:00:00Z"), WEEKDAYS),
                new Stretch(utc("2026-10-14T00:00:00Z"), ALL_DAY),
                new Stretch(utc("2026-10-15T00:00:00Z"), WEEKDAYS));
        Clock clock = new Clock(3, utc("2026-10-14T00:00:00Z"), null, null, null);
        assertThat(SlaClocks.catchUp(clock, versions))
                .isEqualTo(new Clock(3 + 24 * 3600, utc("2026-10-15T00:00:00Z"), null, null, null));
    }

    @Test
    @DisplayName("S8A-AC-20 one version changes nothing")
    void catchUpOneVersion() {
        Clock running = new Clock(100, T0, null, WARN, DUE);
        assertThat(SlaClocks.catchUp(running, List.of(new Stretch(utc("2026-10-01T00:00:00Z"), ALL_DAY))))
                .isEqualTo(running);
        Clock paused = new Clock(100, null, null, null, null);
        assertThat(SlaClocks.catchUp(paused, List.of(new Stretch(utc("2026-10-01T00:00:00Z"), ALL_DAY))))
                .isEqualTo(paused);
    }

    @Test
    @DisplayName("S8A-AC-20 a stopped clock, settled or paused, stays as it is")
    void catchUpStopped() {
        List<Stretch> versions = List.of(
                new Stretch(utc("2026-10-01T00:00:00Z"), WEEKDAYS),
                new Stretch(utc("2026-10-14T00:00:00Z"), ALL_DAY),
                new Stretch(utc("2026-10-15T00:00:00Z"), THURSDAY_HOUR),
                new Stretch(utc("2026-10-16T00:00:00Z"), WEEKDAYS));
        Clock met = new Clock(900, null, Outcome.MET, WARN, DUE);
        Clock missed = new Clock(90_000, null, Outcome.MISSED, WARN, DUE);
        Clock none = new Clock(5, null, Outcome.NONE, null, null);
        Clock paused = new Clock(600, null, null, null, null);
        assertThat(SlaClocks.catchUp(met, versions)).isEqualTo(met);
        assertThat(SlaClocks.catchUp(missed, versions)).isEqualTo(missed);
        assertThat(SlaClocks.catchUp(none, versions)).isEqualTo(none);
        assertThat(SlaClocks.catchUp(paused, versions)).isEqualTo(paused);
    }

    // ---- stop ----

    @Test
    @DisplayName("S8A-AC-13 stopping with the time used equal to the goal is met, and the due times stay")
    void stopMetAtGoal() {
        Clock clock = new Clock(600, T0, null, WARN, DUE);
        // 600 + 3000 = 3600 seconds, the goal of 60 minutes
        assertThat(SlaClocks.stop(clock, at(3000), 60, ALL_DAY))
                .isEqualTo(new Clock(3600, null, Outcome.MET, WARN, DUE));
        assertThat(SlaClocks.stop(clock, at(2999), 60, ALL_DAY))
                .isEqualTo(new Clock(3599, null, Outcome.MET, WARN, DUE));
        assertThat(SlaClocks.stop(clock, T0, 60, ALL_DAY)).isEqualTo(new Clock(600, null, Outcome.MET, WARN, DUE));
    }

    @Test
    @DisplayName("S8A-AC-13 one second over the goal is missed")
    void stopMissed() {
        Clock clock = new Clock(600, T0, null, WARN, DUE);
        assertThat(SlaClocks.stop(clock, at(3001), 60, ALL_DAY))
                .isEqualTo(new Clock(3601, null, Outcome.MISSED, WARN, DUE));
        assertThat(SlaClocks.stop(clock, at(3000), 59, ALL_DAY))
                .isEqualTo(new Clock(3600, null, Outcome.MISSED, WARN, DUE));
        assertThat(SlaClocks.stop(new Clock(4000, T0, null, null, null), T0, 60, ALL_DAY))
                .isEqualTo(new Clock(4000, null, Outcome.MISSED, null, null));
    }

    @Test
    @DisplayName("S8A-AC-13 stopping counts business time only")
    void stopCountsBusinessTime() {
        // Monday 17:00 KST to Tuesday 10:00 KST: 2 h of business time, goal 120 minutes
        Clock clock = new Clock(0, kst("2026-10-12T17:00:00"), null, WARN, DUE);
        assertThat(SlaClocks.stop(clock, kst("2026-10-13T10:00:00"), 120, WEEKDAYS))
                .isEqualTo(new Clock(7200, null, Outcome.MET, WARN, DUE));
        assertThat(SlaClocks.stop(clock, kst("2026-10-13T10:00:01"), 120, WEEKDAYS))
                .isEqualTo(new Clock(7201, null, Outcome.MISSED, WARN, DUE));
    }

    @Test
    @DisplayName("S8A-AC-13 stopping without a goal settles as no goal")
    void stopWithoutGoal() {
        Clock clock = new Clock(600, T0, null, null, null);
        assertThat(SlaClocks.stop(clock, at(100_000), null, ALL_DAY))
                .isEqualTo(new Clock(100_600, null, Outcome.NONE, null, null));
        assertThat(SlaClocks.stop(new Clock(0, T0, null, WARN, DUE), T0, null, ALL_DAY))
                .isEqualTo(new Clock(0, null, Outcome.NONE, WARN, DUE));
    }

    @Test
    @DisplayName("S8A-AC-15 a stopped clock is not stopped again")
    void stopStopped() {
        Clock met = new Clock(900, null, Outcome.MET, WARN, DUE);
        Clock missed = new Clock(90_000, null, Outcome.MISSED, WARN, DUE);
        Clock none = new Clock(5, null, Outcome.NONE, null, null);
        Clock paused = new Clock(600, null, null, null, null);
        assertThat(SlaClocks.stop(met, at(10_000), 1, ALL_DAY)).isEqualTo(met);
        assertThat(SlaClocks.stop(missed, at(10_000), 100_000, ALL_DAY)).isEqualTo(missed);
        assertThat(SlaClocks.stop(none, at(10_000), 60, ALL_DAY)).isEqualTo(none);
        assertThat(SlaClocks.stop(paused, at(10_000), 60, ALL_DAY)).isEqualTo(paused);
    }

    // ---- pause ----

    @Test
    @DisplayName("S8A-AC-16 pausing adds the time, settles nothing, and clears the due times")
    void pauseRunning() {
        Clock clock = new Clock(600, T0, null, WARN, DUE);
        assertThat(SlaClocks.pause(clock, at(4000), ALL_DAY)).isEqualTo(new Clock(4600, null, null, null, null));
        assertThat(SlaClocks.pause(clock, T0, ALL_DAY)).isEqualTo(new Clock(600, null, null, null, null));
        Clock weekday = new Clock(10, kst("2026-10-16T17:30:00"), null, WARN, DUE);
        // Friday 17:30 to 18:00, then Monday 09:00 to 09:15
        assertThat(SlaClocks.pause(weekday, kst("2026-10-19T09:15:00"), WEEKDAYS))
                .isEqualTo(new Clock(10 + 1800 + 900, null, null, null, null));
    }

    @Test
    @DisplayName("S8A-AC-16 pausing a stopped clock changes nothing")
    void pauseStopped() {
        Clock met = new Clock(900, null, Outcome.MET, WARN, DUE);
        Clock paused = new Clock(600, null, null, null, null);
        Clock none = new Clock(5, null, Outcome.NONE, null, null);
        assertThat(SlaClocks.pause(met, at(10_000), ALL_DAY)).isEqualTo(met);
        assertThat(SlaClocks.pause(paused, at(10_000), ALL_DAY)).isEqualTo(paused);
        assertThat(SlaClocks.pause(none, at(10_000), ALL_DAY)).isEqualTo(none);
    }

    // ---- resume ----

    @Test
    @DisplayName("S8A-AC-16 a restore runs a paused clock on from the restore, keeping the time counted")
    void resumePaused() {
        Clock paused = new Clock(4600, null, null, null, null);
        Clock resumed = SlaClocks.resume(paused, at(90_000));
        assertThat(resumed.spent()).isEqualTo(4600);
        assertThat(resumed.since()).isEqualTo(at(90_000));
        assertThat(resumed.outcome()).isNull();
        assertThat(resumed.running()).isTrue();
    }

    @Test
    @DisplayName("S8A-AC-16 a restore leaves a settled clock and a running clock as they are")
    void resumeOthers() {
        Clock met = new Clock(900, null, Outcome.MET, WARN, DUE);
        Clock missed = new Clock(90_000, null, Outcome.MISSED, WARN, DUE);
        Clock none = new Clock(5, null, Outcome.NONE, null, null);
        Clock running = new Clock(100, T0, null, WARN, DUE);
        assertThat(SlaClocks.resume(met, at(90_000))).isEqualTo(met);
        assertThat(SlaClocks.resume(missed, at(90_000))).isEqualTo(missed);
        assertThat(SlaClocks.resume(none, at(90_000))).isEqualTo(none);
        assertThat(SlaClocks.resume(running, at(90_000))).isEqualTo(running);
    }

    @Test
    @DisplayName("S8A-AC-16 trash and restore: the time in the trash is not counted")
    void trashAndRestore() {
        Clock clock = new Clock(0, T0, null, null, null);
        Clock paused = SlaClocks.pause(clock, at(1000), ALL_DAY);
        Clock resumed = SlaClocks.resume(paused, at(50_000));
        Clock stopped = SlaClocks.stop(resumed, at(50_500), 25, ALL_DAY);
        // 1000 before the trash, 500 after the restore: 1500 = the goal of 25 minutes
        assertThat(stopped.spent()).isEqualTo(1500);
        assertThat(stopped.outcome()).isEqualTo(Outcome.MET);
        assertThat(stopped.since()).isNull();
    }

    // ---- reopen ----

    @Test
    @DisplayName("S8A-AC-15 reopening clears the outcome and counts on from the time before")
    void reopenSettled() {
        for (Outcome outcome : Outcome.values()) {
            Clock settled = new Clock(5000, null, outcome, WARN, DUE);
            Clock reopened = SlaClocks.reopen(settled, at(70_000));
            assertThat(reopened.spent()).as("%s", outcome).isEqualTo(5000);
            assertThat(reopened.since()).as("%s", outcome).isEqualTo(at(70_000));
            assertThat(reopened.outcome()).as("%s", outcome).isNull();
            assertThat(reopened.running()).as("%s", outcome).isTrue();
        }
    }

    @Test
    @DisplayName("S8A-AC-15 reopening a running clock changes nothing")
    void reopenRunning() {
        Clock running = new Clock(100, T0, null, WARN, DUE);
        assertThat(SlaClocks.reopen(running, at(70_000))).isEqualTo(running);
    }

    @Test
    @DisplayName("S8A-AC-15 settled, reopened, and settled again: the time adds up")
    void reopenThenStop() {
        Clock first = SlaClocks.stop(new Clock(0, T0, null, null, null), at(3000), 60, ALL_DAY);
        assertThat(first.outcome()).isEqualTo(Outcome.MET);
        Clock reopened = SlaClocks.reopen(first, at(10_000));
        Clock again = SlaClocks.stop(reopened, at(10_601), 60, ALL_DAY);
        // 3000 + 601 = 3601, one second over the goal
        assertThat(again.spent()).isEqualTo(3601);
        assertThat(again.outcome()).isEqualTo(Outcome.MISSED);
    }

    // ---- deadlines ----

    @Test
    @DisplayName("S8A-AC-10 the due time is the goal less the time used from since, the warning 80% likewise")
    void deadlinesAllDay() {
        Clock clock = new Clock(600, T0, null, null, null);
        // goal 60 min = 3600 s: due 3600 - 600 = 3000 s on; warning 2880 - 600 = 2280 s on
        assertThat(SlaClocks.deadlines(clock, 60, ALL_DAY)).isEqualTo(new Clock(600, T0, null, at(2280), at(3000)));
        // goal 1 min: warning at 48 s, due at 60 s
        assertThat(SlaClocks.deadlines(new Clock(0, T0, null, null, null), 1, ALL_DAY))
                .isEqualTo(new Clock(0, T0, null, at(48), at(60)));
        // goal 7 min = 420 s: warning at 336 s
        assertThat(SlaClocks.deadlines(new Clock(0, T0, null, WARN, DUE), 7, ALL_DAY))
                .isEqualTo(new Clock(0, T0, null, at(336), at(420)));
        // goal 525,600 min all day: 365 days, warning at 292 days
        assertThat(SlaClocks.deadlines(new Clock(0, T0, null, null, null), 525_600, ALL_DAY))
                .isEqualTo(new Clock(0, T0, null, at(292L * 86_400), at(365L * 86_400)));
    }

    @Test
    @DisplayName("S8A-AC-17 the due times follow business hours")
    void deadlinesWeekdays() {
        // Monday 17:00 KST, goal 120 min: 1 h on Monday, 1 h from Tuesday 09:00 -> due Tuesday 10:00
        // warning 96 min: 60 on Monday, 36 on Tuesday -> Tuesday 09:36
        Clock clock = new Clock(0, kst("2026-10-12T17:00:00"), null, null, null);
        assertThat(SlaClocks.deadlines(clock, 120, WEEKDAYS))
                .isEqualTo(new Clock(
                        0, kst("2026-10-12T17:00:00"), null, kst("2026-10-13T09:36:00"), kst("2026-10-13T10:00:00")));
        // from Saturday, goal 60 min with 30 min used: due Monday 09:30, warning (48 - 30 = 18 min) Monday 09:18
        Clock saturday = new Clock(1800, kst("2026-10-17T12:00:00"), null, null, null);
        assertThat(SlaClocks.deadlines(saturday, 60, WEEKDAYS))
                .isEqualTo(new Clock(
                        1800, kst("2026-10-17T12:00:00"), null, kst("2026-10-19T09:18:00"), kst("2026-10-19T09:30:00")));
        // a goal that runs out exactly at the closing
        Clock late = new Clock(0, kst("2026-10-12T17:00:00"), null, null, null);
        assertThat(SlaClocks.deadlines(late, 60, WEEKDAYS).dueAt()).isEqualTo(kst("2026-10-12T18:00:00"));
        assertThat(SlaClocks.deadlines(late, 60, WEEKDAYS).warnAt()).isEqualTo(kst("2026-10-12T17:48:00"));
    }

    @Test
    @DisplayName("design 0절 7 a goal already used up gives since for both times")
    void deadlinesUsedUp() {
        Instant evening = kst("2026-10-12T20:00:00");
        // used exactly the goal
        assertThat(SlaClocks.deadlines(new Clock(3600, evening, null, null, null), 60, WEEKDAYS))
                .isEqualTo(new Clock(3600, evening, null, evening, evening));
        // used more than the goal
        assertThat(SlaClocks.deadlines(new Clock(9000, evening, null, null, null), 60, WEEKDAYS))
                .isEqualTo(new Clock(9000, evening, null, evening, evening));
        // used past 80% but not the whole goal: warning at since, due later
        // 3000 used of 3600: due 600 s from Tuesday 09:00, warning (2880 - 3000 < 0) at since
        assertThat(SlaClocks.deadlines(new Clock(3000, evening, null, null, null), 60, WEEKDAYS))
                .isEqualTo(new Clock(3000, evening, null, evening, kst("2026-10-13T09:10:00")));
        // exactly 80% used: warning at since
        assertThat(SlaClocks.deadlines(new Clock(2880, T0, null, null, null), 60, ALL_DAY))
                .isEqualTo(new Clock(2880, T0, null, T0, at(720)));
    }

    @Test
    @DisplayName("S8A-AC-19 the due times are kept to the second")
    void deadlinesToTheSecond() {
        Instant since = T0.plusMillis(700);
        Clock clock = new Clock(0, since, null, null, null);
        // 60 s on from 00:00:00.7 is 00:01:00.7, kept as 00:01:00; 48 s on is 00:00:48.7, kept as 00:00:48
        assertThat(SlaClocks.deadlines(clock, 1, ALL_DAY)).isEqualTo(new Clock(0, since, null, at(48), at(60)));
        // used up: since, kept to the second
        Clock usedUp = new Clock(60, since, null, null, null);
        assertThat(SlaClocks.deadlines(usedUp, 1, ALL_DAY)).isEqualTo(new Clock(60, since, null, T0, T0));
    }

    @Test
    @DisplayName("S8A-AC-17 without a goal a running clock has no warning or due time")
    void deadlinesWithoutGoal() {
        Clock clock = new Clock(600, T0, null, WARN, DUE);
        assertThat(SlaClocks.deadlines(clock, null, ALL_DAY)).isEqualTo(new Clock(600, T0, null, null, null));
    }

    @Test
    @DisplayName("S8A-AC-17 a stopped clock keeps its due times")
    void deadlinesStopped() {
        Clock met = new Clock(900, null, Outcome.MET, WARN, DUE);
        Clock paused = new Clock(600, null, null, null, null);
        Clock none = new Clock(5, null, Outcome.NONE, null, null);
        assertThat(SlaClocks.deadlines(met, 1, ALL_DAY)).isEqualTo(met);
        assertThat(SlaClocks.deadlines(met, null, ALL_DAY)).isEqualTo(met);
        assertThat(SlaClocks.deadlines(paused, 60, ALL_DAY)).isEqualTo(paused);
        assertThat(SlaClocks.deadlines(none, 60, ALL_DAY)).isEqualTo(none);
    }

    @Test
    @DisplayName("S8A-AC-20 caught up and worked out again: the remaining goal runs from the new version's start")
    void catchUpThenDeadlines() {
        List<Stretch> versions = List.of(
                new Stretch(utc("2026-10-01T00:00:00Z"), ALL_DAY),
                new Stretch(utc("2026-10-12T02:00:00Z"), WEEKDAYS));
        // running since Monday 00:00 UTC; 2 h all day, then the weekday calendar from Monday 02:00 UTC (11:00 KST)
        Clock caught = SlaClocks.catchUp(new Clock(0, T0, null, null, null), versions);
        assertThat(caught).isEqualTo(new Clock(7200, utc("2026-10-12T02:00:00Z"), null, null, null));
        // goal 600 min: 36,000 - 7200 = 28,800 s = 8 h from Monday 11:00 KST -> 7 h on Monday, 1 h Tuesday -> 10:00
        // warning 480 min: 28,800 - 7200 = 21,600 s = 6 h -> Monday 17:00 KST
        Clock worked = SlaClocks.deadlines(caught, 600, WEEKDAYS);
        assertThat(worked.dueAt()).isEqualTo(kst("2026-10-13T10:00:00"));
        assertThat(worked.warnAt()).isEqualTo(kst("2026-10-12T17:00:00"));
    }

    // ---- state ----

    @Test
    @DisplayName("S8A-AC-18 OK before the warning, WARNING from it up to the due time, BREACHED only after")
    void stateEdges() {
        Clock clock = new Clock(0, T0, null, WARN, DUE);
        assertThat(SlaClocks.state(clock, T0)).isEqualTo(State.OK);
        assertThat(SlaClocks.state(clock, WARN.minusSeconds(1))).isEqualTo(State.OK);
        assertThat(SlaClocks.state(clock, WARN.minusNanos(1))).isEqualTo(State.OK);
        assertThat(SlaClocks.state(clock, WARN)).isEqualTo(State.WARNING);
        assertThat(SlaClocks.state(clock, WARN.plusNanos(1))).isEqualTo(State.WARNING);
        assertThat(SlaClocks.state(clock, DUE.minusNanos(1))).isEqualTo(State.WARNING);
        assertThat(SlaClocks.state(clock, DUE)).isEqualTo(State.WARNING);
        assertThat(SlaClocks.state(clock, DUE.plusNanos(1))).isEqualTo(State.BREACHED);
        assertThat(SlaClocks.state(clock, DUE.plusSeconds(1))).isEqualTo(State.BREACHED);
        assertThat(SlaClocks.state(clock, DUE.plusSeconds(86_400 * 30))).isEqualTo(State.BREACHED);
    }

    @Test
    @DisplayName("S8A-AC-18 a goal already used up: warning and due at since, breached just after")
    void stateUsedUp() {
        Clock clock = new Clock(3600, T0, null, T0, T0);
        assertThat(SlaClocks.state(clock, T0)).isEqualTo(State.WARNING);
        assertThat(SlaClocks.state(clock, T0.plusNanos(1))).isEqualTo(State.BREACHED);
        assertThat(SlaClocks.state(clock, T0.minusSeconds(1))).isEqualTo(State.OK);
    }

    @Test
    @DisplayName("S8A-AC-18 a settled goal is MET or MISSED whatever the time")
    void stateSettled() {
        Clock met = new Clock(900, null, Outcome.MET, WARN, DUE);
        Clock missed = new Clock(90_000, null, Outcome.MISSED, WARN, DUE);
        for (Instant now : List.of(T0, WARN, DUE, DUE.plusSeconds(1), DUE.plusSeconds(86_400 * 400))) {
            assertThat(SlaClocks.state(met, now)).as("%s", now).isEqualTo(State.MET);
            assertThat(SlaClocks.state(missed, now)).as("%s", now).isEqualTo(State.MISSED);
        }
        assertThat(SlaClocks.state(new Clock(900, null, Outcome.MET, null, null), DUE)).isEqualTo(State.MET);
        assertThat(SlaClocks.state(new Clock(900, null, Outcome.MISSED, null, null), T0)).isEqualTo(State.MISSED);
    }

    @Test
    @DisplayName("S8A-AC-18 no state when settled without a goal, or without a due time")
    void stateNone() {
        assertThat(SlaClocks.state(new Clock(5, null, Outcome.NONE, null, null), T0)).isNull();
        assertThat(SlaClocks.state(new Clock(5, null, Outcome.NONE, WARN, DUE), DUE.plusSeconds(1))).isNull();
        // running without a goal
        assertThat(SlaClocks.state(new Clock(5, T0, null, null, null), DUE.plusSeconds(1))).isNull();
        // paused in the trash
        assertThat(SlaClocks.state(new Clock(600, null, null, null, null), DUE.plusSeconds(1))).isNull();
    }

    // ---- remainingMinutes ----

    @Test
    @DisplayName("S8A-AC-19 minutes left are whole minutes rounded down")
    void remainingPositive() {
        Clock clock = new Clock(0, T0, null, null, null);
        assertThat(SlaClocks.remainingMinutes(clock, T0, 60, ALL_DAY)).isEqualTo(60L);
        assertThat(SlaClocks.remainingMinutes(clock, at(1), 60, ALL_DAY)).isEqualTo(59L);
        assertThat(SlaClocks.remainingMinutes(clock, at(59), 60, ALL_DAY)).isEqualTo(59L);
        assertThat(SlaClocks.remainingMinutes(clock, at(60), 60, ALL_DAY)).isEqualTo(59L);
        assertThat(SlaClocks.remainingMinutes(clock, at(61), 60, ALL_DAY)).isEqualTo(58L);
        assertThat(SlaClocks.remainingMinutes(clock, at(3540), 60, ALL_DAY)).isEqualTo(1L);
        assertThat(SlaClocks.remainingMinutes(clock, at(3541), 60, ALL_DAY)).isEqualTo(0L);
        assertThat(SlaClocks.remainingMinutes(clock, at(3600), 60, ALL_DAY)).isEqualTo(0L);
    }

    @Test
    @DisplayName("S8A-AC-19 after the goal ran out the minutes past are negative, rounded up in size")
    void remainingNegative() {
        Clock clock = new Clock(0, T0, null, null, null);
        assertThat(SlaClocks.remainingMinutes(clock, at(3601), 60, ALL_DAY)).isEqualTo(-1L);
        assertThat(SlaClocks.remainingMinutes(clock, at(3630), 60, ALL_DAY)).isEqualTo(-1L);
        assertThat(SlaClocks.remainingMinutes(clock, at(3660), 60, ALL_DAY)).isEqualTo(-1L);
        assertThat(SlaClocks.remainingMinutes(clock, at(3661), 60, ALL_DAY)).isEqualTo(-2L);
        assertThat(SlaClocks.remainingMinutes(clock, at(3600 + 86_400), 60, ALL_DAY)).isEqualTo(-1440L);
    }

    @Test
    @DisplayName("S8A-AC-19 the time counted before since is used too, and only business time counts")
    void remainingWithSpentAndHours() {
        Clock spent = new Clock(3000, T0, null, null, null);
        assertThat(SlaClocks.remainingMinutes(spent, T0, 60, ALL_DAY)).isEqualTo(10L);
        assertThat(SlaClocks.remainingMinutes(spent, at(600), 60, ALL_DAY)).isEqualTo(0L);
        assertThat(SlaClocks.remainingMinutes(spent, at(630), 60, ALL_DAY)).isEqualTo(-1L);
        Clock over = new Clock(9000, T0, null, null, null);
        assertThat(SlaClocks.remainingMinutes(over, T0, 60, ALL_DAY)).isEqualTo(-90L);
        // Monday 17:00 KST to Tuesday 09:30 KST is 1.5 h of business time; goal 120 min
        Clock weekday = new Clock(0, kst("2026-10-12T17:00:00"), null, null, null);
        assertThat(SlaClocks.remainingMinutes(weekday, kst("2026-10-13T09:30:00"), 120, WEEKDAYS)).isEqualTo(30L);
        // the night does not count: at Monday 23:00 still 60 minutes left
        assertThat(SlaClocks.remainingMinutes(weekday, kst("2026-10-12T23:00:00"), 120, WEEKDAYS)).isEqualTo(60L);
        assertThat(SlaClocks.remainingMinutes(weekday, kst("2026-10-13T10:00:30"), 120, WEEKDAYS)).isEqualTo(-1L);
    }

    @Test
    @DisplayName("S8A-AC-19 no minutes left for a stopped clock or without a goal")
    void remainingNone() {
        assertThat(SlaClocks.remainingMinutes(new Clock(0, T0, null, null, null), at(10), null, ALL_DAY)).isNull();
        assertThat(SlaClocks.remainingMinutes(new Clock(600, null, null, null, null), at(10), 60, ALL_DAY)).isNull();
        assertThat(SlaClocks.remainingMinutes(new Clock(600, null, Outcome.MET, WARN, DUE), at(10), 60, ALL_DAY))
                .isNull();
        assertThat(SlaClocks.remainingMinutes(new Clock(600, null, Outcome.NONE, null, null), at(10), null, ALL_DAY))
                .isNull();
    }

    // ---- truncate ----

    @Test
    @DisplayName("S8A-AC-19 times are cut to the second")
    void truncate() {
        assertThat(SlaClocks.truncate(utc("2026-10-16T05:00:00.999999999Z"))).isEqualTo(utc("2026-10-16T05:00:00Z"));
        assertThat(SlaClocks.truncate(utc("2026-10-16T05:00:00.000000001Z"))).isEqualTo(utc("2026-10-16T05:00:00Z"));
        assertThat(SlaClocks.truncate(utc("2026-10-16T05:00:07Z"))).isEqualTo(utc("2026-10-16T05:00:07Z"));
        assertThat(SlaClocks.truncate(utc("1969-12-31T23:59:59.500Z"))).isEqualTo(utc("1969-12-31T23:59:59Z"));
    }
}
