package io.popdesk.notification.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.popdesk.notification.ops.StalledCleanup.Job;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * When a cleanup job counts as stalled (S7B-AC-10, S7B-AC-11; acceptance.md section 5 item 3; slice 7b design
 * sections 4 and 6): more than its interval plus 24 hours after its last success, measured from the round that
 * first held the lock when it never succeeded, with the boundary just before, at, and just after, for a
 * ten-minute and a one-day interval. Written from the Javadoc of {@link StalledCleanup} alone.
 */
class StalledCleanupTest {

    private static final Duration DAY = Duration.ofHours(24);
    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);
    private static final Duration NANO = Duration.ofNanos(1);
    private static final Duration MICRO = Duration.of(1, ChronoUnit.MICROS);
    private static final Instant LAST = Instant.parse("2026-10-16T05:00:00.123456Z");
    private static final Instant FIRST_HELD = Instant.parse("2026-10-10T08:30:00Z");

    @Test
    @DisplayName("5절 3 the grace on top of the interval is exactly 24 hours")
    void grace() {
        assertThat(StalledCleanup.GRACE).isEqualTo(DAY);
        assertThat(StalledCleanup.GRACE.toMinutes()).isEqualTo(24 * 60);
    }

    @ParameterizedTest
    @ValueSource(longs = {10, 24 * 60, 1, 7 * 24 * 60})
    @DisplayName("S7B-AC-10 not stalled just before and at interval plus a day, stalled just after")
    void boundaryAfterLastSuccess(long intervalMinutes) {
        Duration interval = Duration.ofMinutes(intervalMinutes);
        Instant edge = LAST.plus(interval).plus(DAY);
        assertThat(StalledCleanup.stalled(LAST, FIRST_HELD, interval, edge.minus(NANO))).isFalse();
        assertThat(StalledCleanup.stalled(LAST, FIRST_HELD, interval, edge.minus(MICRO))).isFalse();
        assertThat(StalledCleanup.stalled(LAST, FIRST_HELD, interval, edge)).isFalse();
        assertThat(StalledCleanup.stalled(LAST, FIRST_HELD, interval, edge.plus(NANO))).isTrue();
        assertThat(StalledCleanup.stalled(LAST, FIRST_HELD, interval, edge.plus(MICRO))).isTrue();
        assertThat(StalledCleanup.stalled(LAST, FIRST_HELD, interval, edge.plus(Duration.ofDays(30)))).isTrue();
    }

    @Test
    @DisplayName("S7B-AC-10 a ten-minute job, with the times written out")
    void tenMinutesWrittenOut() {
        Instant last = Instant.parse("2026-10-16T05:00:00Z");
        assertThat(StalledCleanup.stalled(last, FIRST_HELD, TEN_MINUTES, Instant.parse("2026-10-17T05:09:59Z")))
                .isFalse();
        assertThat(StalledCleanup.stalled(last, FIRST_HELD, TEN_MINUTES, Instant.parse("2026-10-17T05:10:00Z")))
                .isFalse();
        assertThat(StalledCleanup.stalled(last, FIRST_HELD, TEN_MINUTES, Instant.parse("2026-10-17T05:10:01Z")))
                .isTrue();
    }

    @Test
    @DisplayName("S7B-AC-10 a daily job is not stalled after one missed run (24 hours 1 minute) but is after 48 hours")
    void dailyJobWrittenOut() {
        Instant last = Instant.parse("2026-10-16T05:00:00Z");
        assertThat(StalledCleanup.stalled(last, FIRST_HELD, DAY, Instant.parse("2026-10-17T05:01:00Z"))).isFalse();
        assertThat(StalledCleanup.stalled(last, FIRST_HELD, DAY, Instant.parse("2026-10-18T04:59:59Z"))).isFalse();
        assertThat(StalledCleanup.stalled(last, FIRST_HELD, DAY, Instant.parse("2026-10-18T05:00:00Z"))).isFalse();
        assertThat(StalledCleanup.stalled(last, FIRST_HELD, DAY, Instant.parse("2026-10-18T05:00:01Z"))).isTrue();
    }

    @Test
    @DisplayName("S7B-AC-10 the interval counts: the same lateness stalls a ten-minute job but not a daily one")
    void intervalCounts() {
        Instant now = LAST.plus(DAY).plus(Duration.ofHours(1));
        assertThat(StalledCleanup.stalled(LAST, FIRST_HELD, TEN_MINUTES, now)).isTrue();
        assertThat(StalledCleanup.stalled(LAST, FIRST_HELD, DAY, now)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(longs = {10, 24 * 60})
    @DisplayName("S7B-AC-10 a job that never succeeded is measured from the round that first held the lock")
    void neverSucceeded(long intervalMinutes) {
        Duration interval = Duration.ofMinutes(intervalMinutes);
        Instant edge = FIRST_HELD.plus(interval).plus(DAY);
        assertThat(StalledCleanup.stalled(null, FIRST_HELD, interval, FIRST_HELD)).isFalse();
        assertThat(StalledCleanup.stalled(null, FIRST_HELD, interval, edge.minus(NANO))).isFalse();
        assertThat(StalledCleanup.stalled(null, FIRST_HELD, interval, edge)).isFalse();
        assertThat(StalledCleanup.stalled(null, FIRST_HELD, interval, edge.plus(NANO))).isTrue();
    }

    @Test
    @DisplayName("S7B-AC-10 the first-held time is not used when there is a last success")
    void firstHeldIgnoredWithLastSuccess() {
        Instant longAgo = Instant.parse("2000-01-01T00:00:00Z");
        Instant later = Instant.parse("2030-01-01T00:00:00Z");
        Instant edge = LAST.plus(TEN_MINUTES).plus(DAY);
        // a first-held time long ago would make it stalled, one far ahead would not
        assertThat(StalledCleanup.stalled(LAST, longAgo, TEN_MINUTES, edge)).isFalse();
        assertThat(StalledCleanup.stalled(LAST, longAgo, TEN_MINUTES, LAST)).isFalse();
        assertThat(StalledCleanup.stalled(LAST, later, TEN_MINUTES, edge.plus(NANO))).isTrue();
    }

    @Test
    @DisplayName("S7B-AC-10 a time before the last success is not stalled")
    void nowBeforeLastSuccess() {
        assertThat(StalledCleanup.stalled(LAST, FIRST_HELD, TEN_MINUTES, LAST.minus(NANO))).isFalse();
        assertThat(StalledCleanup.stalled(LAST, FIRST_HELD, TEN_MINUTES, LAST.minus(Duration.ofDays(400))))
                .isFalse();
        assertThat(StalledCleanup.stalled(LAST, FIRST_HELD, DAY, LAST.minus(Duration.ofDays(3)))).isFalse();
        assertThat(StalledCleanup.stalled(null, FIRST_HELD, TEN_MINUTES, FIRST_HELD.minus(Duration.ofDays(3))))
                .isFalse();
    }

    @Test
    @DisplayName("S7B-AC-11 no jobs gives false")
    void anyStalledEmpty() {
        assertThat(StalledCleanup.anyStalled(List.of(), FIRST_HELD, LAST.plus(Duration.ofDays(400)))).isFalse();
        assertThat(StalledCleanup.anyStalled(Set.of(), FIRST_HELD, FIRST_HELD)).isFalse();
    }

    @Test
    @DisplayName("S7B-AC-11 one late job among several is enough, wherever it stands")
    void anyStalledOneLate() {
        Instant now = LAST.plus(TEN_MINUTES).plus(DAY).plus(NANO);
        Job late = new Job("attachment-orphans", TEN_MINUTES, LAST);
        Job fine = new Job("audit-retention", DAY, LAST);
        Job fresh = new Job("trash-purge", TEN_MINUTES, now);
        assertThat(StalledCleanup.anyStalled(List.of(late, fine, fresh), FIRST_HELD, now)).isTrue();
        assertThat(StalledCleanup.anyStalled(List.of(fine, fresh, late), FIRST_HELD, now)).isTrue();
        assertThat(StalledCleanup.anyStalled(List.of(fine, late, fresh), FIRST_HELD, now)).isTrue();
        assertThat(StalledCleanup.anyStalled(List.of(late), FIRST_HELD, now)).isTrue();
    }

    @Test
    @DisplayName("S7B-AC-11 no job past interval plus a day gives false, also exactly at the edge")
    void anyStalledNoneLate() {
        Instant now = LAST.plus(TEN_MINUTES).plus(DAY);
        Job atEdge = new Job("attachment-orphans", TEN_MINUTES, LAST);
        Job daily = new Job("audit-retention", DAY, LAST);
        Job ahead = new Job("trash-purge", TEN_MINUTES, now.plus(DAY));
        assertThat(StalledCleanup.anyStalled(List.of(atEdge, daily, ahead), FIRST_HELD, now)).isFalse();
        assertThat(StalledCleanup.anyStalled(List.of(atEdge, daily, ahead), FIRST_HELD, now.plus(NANO))).isTrue();
    }

    @Test
    @DisplayName("S7B-AC-11 several late jobs still give one true")
    void anyStalledAllLate() {
        Instant now = LAST.plus(Duration.ofDays(5));
        List<Job> jobs = List.of(
                new Job("attachment-orphans", TEN_MINUTES, LAST),
                new Job("audit-retention", DAY, LAST),
                new Job("trash-purge", TEN_MINUTES, null));
        assertThat(StalledCleanup.anyStalled(jobs, FIRST_HELD, now)).isTrue();
    }

    @Test
    @DisplayName("S7B-AC-10 a job without a last success is judged from the first-held time in the list too")
    void anyStalledNeverSucceeded() {
        Job never = new Job("trash-purge", TEN_MINUTES, null);
        Instant edge = FIRST_HELD.plus(TEN_MINUTES).plus(DAY);
        assertThat(StalledCleanup.anyStalled(List.of(never), FIRST_HELD, edge)).isFalse();
        assertThat(StalledCleanup.anyStalled(List.of(never), FIRST_HELD, edge.plus(NANO))).isTrue();
    }

    @Test
    @DisplayName("S7B-AC-10 only the jobs handed in are judged; the name plays no part")
    void onlyListedJobsJudged() {
        Instant now = LAST.plus(DAY).plus(Duration.ofHours(1));
        // an old job name that only remains in the run record, long past, is not in the list
        Job current = new Job("audit-retention", DAY, LAST);
        assertThat(StalledCleanup.anyStalled(List.of(current), FIRST_HELD, now)).isFalse();
        // the same times under another name, or under a repeated name, give the same answer
        Job renamed = new Job("something-else", DAY, LAST);
        assertThat(StalledCleanup.anyStalled(List.of(renamed, renamed), FIRST_HELD, now)).isFalse();
        Job lateSameName = new Job("audit-retention", TEN_MINUTES, LAST);
        assertThat(StalledCleanup.anyStalled(List.of(current, lateSameName), FIRST_HELD, now)).isTrue();
        assertThat(StalledCleanup.anyStalled(List.of(new Job("", TEN_MINUTES, LAST)), FIRST_HELD, now)).isTrue();
    }

    @Test
    @DisplayName("S7B-AC-10 a job keeps what it was given")
    void jobKeepsValues() {
        Job job = new Job("audit-retention", DAY, LAST);
        assertThat(job.name()).isEqualTo("audit-retention");
        assertThat(job.interval()).isEqualTo(DAY);
        assertThat(job.lastSuccess()).isEqualTo(LAST);
        assertThat(new Job("trash-purge", TEN_MINUTES, null).lastSuccess()).isNull();
    }

    @Test
    @DisplayName("S7B-AC-10 a job without a name or without an interval is refused")
    void jobRefusesNulls() {
        assertThatThrownBy(() -> new Job(null, DAY, LAST)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new Job("audit-retention", null, LAST)).isInstanceOf(RuntimeException.class);
    }
}
