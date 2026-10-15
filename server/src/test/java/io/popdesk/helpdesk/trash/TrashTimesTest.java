package io.popdesk.helpdesk.trash;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * When an issue in the trash is due to be purged (TRASH-3, S7A-AC-31; slice 7a design section 7): 30 days
 * after it was deleted, on the instant line, with the boundary just before, at, and just after. Written from
 * the Javadoc of {@link TrashTimes} alone.
 */
class TrashTimesTest {

    private static final Duration THIRTY_DAYS = Duration.ofHours(30 * 24);
    private static final Duration MICRO = Duration.of(1, ChronoUnit.MICROS);
    private static final Instant DELETED = Instant.parse("2026-10-16T05:00:00.123456Z");

    @Test
    @DisplayName("TRASH-3 an issue stays in the trash for 30 times 24 hours")
    void kept() {
        assertThat(TrashTimes.KEPT).isEqualTo(THIRTY_DAYS);
        assertThat(TrashTimes.KEPT.toHours()).isEqualTo(720);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "2026-10-16T05:00:00.123456Z",
        "1970-01-01T00:00:00Z",
        "1969-12-31T23:59:59.999999Z",
        "2026-02-28T23:59:59Z",
        "2028-02-29T12:00:00Z",
        "2026-12-31T23:59:59.999999999Z"
    })
    @DisplayName("S7A-AC-31 the purge time is the delete time plus exactly 30 times 24 hours")
    void purgeAt(String deleted) {
        Instant deletedAt = Instant.parse(deleted);
        assertThat(TrashTimes.purgeAt(deletedAt)).isEqualTo(deletedAt.plus(THIRTY_DAYS));
        assertThat(Duration.between(deletedAt, TrashTimes.purgeAt(deletedAt))).isEqualTo(THIRTY_DAYS);
    }

    @Test
    @DisplayName("S7A-AC-31 the purge time of a known instant, written out")
    void purgeAtWrittenOut() {
        assertThat(TrashTimes.purgeAt(DELETED)).isEqualTo(Instant.parse("2026-11-15T05:00:00.123456Z"));
    }

    @Test
    @DisplayName("TRASH-3 a daylight saving change in the 30 days moves the wall clock, not the instant")
    void daylightSavingSpring() {
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        // 12:00 CET (UTC+1), with the change to CEST (UTC+2) on 2026-03-29 in between
        Instant deletedAt = LocalDateTime.parse("2026-03-20T12:00:00").atZone(berlin).toInstant();
        assertThat(deletedAt).isEqualTo(Instant.parse("2026-03-20T11:00:00Z"));
        Instant purgeAt = TrashTimes.purgeAt(deletedAt);
        assertThat(purgeAt).isEqualTo(Instant.parse("2026-04-19T11:00:00Z"));
        assertThat(purgeAt.atZone(berlin).toLocalDateTime()).isEqualTo(LocalDateTime.parse("2026-04-19T13:00:00"));
    }

    @Test
    @DisplayName("TRASH-3 the change back from summer time does not move the instant either")
    void daylightSavingAutumn() {
        ZoneId newYork = ZoneId.of("America/New_York");
        // 12:00 EDT (UTC-4), with the change to EST (UTC-5) on 2026-11-01 in between
        Instant deletedAt = LocalDateTime.parse("2026-10-20T12:00:00").atZone(newYork).toInstant();
        Instant purgeAt = TrashTimes.purgeAt(deletedAt);
        assertThat(purgeAt).isEqualTo(Instant.parse("2026-11-19T16:00:00Z"));
        assertThat(purgeAt.atZone(newYork).toLocalDateTime()).isEqualTo(LocalDateTime.parse("2026-11-19T11:00:00"));
        assertThat(TrashTimes.due(deletedAt, Instant.parse("2026-11-19T16:00:00Z").minus(MICRO))).isFalse();
        assertThat(TrashTimes.due(deletedAt, Instant.parse("2026-11-19T16:00:00Z"))).isTrue();
    }

    @Test
    @DisplayName("S7A-AC-31 at the very instant of the purge time the issue is due")
    void dueAtPurgeTime() {
        assertThat(TrashTimes.due(DELETED, DELETED.plus(THIRTY_DAYS))).isTrue();
        assertThat(TrashTimes.due(DELETED, TrashTimes.purgeAt(DELETED))).isTrue();
    }

    @Test
    @DisplayName("S7A-AC-31 a microsecond before the purge time the issue is not due")
    void notDueJustBefore() {
        assertThat(TrashTimes.due(DELETED, DELETED.plus(THIRTY_DAYS).minus(MICRO))).isFalse();
    }

    @Test
    @DisplayName("S7A-AC-31 a microsecond after the purge time the issue is due")
    void dueJustAfter() {
        assertThat(TrashTimes.due(DELETED, DELETED.plus(THIRTY_DAYS).plus(MICRO))).isTrue();
    }

    @Test
    @DisplayName("S7A-AC-31 long after the purge time the issue is still due")
    void dueLongAfter() {
        assertThat(TrashTimes.due(DELETED, DELETED.plus(Duration.ofDays(365)))).isTrue();
    }

    @Test
    @DisplayName("S7A-AC-31 an issue less than 30 days in the trash is not due")
    void notDueWithinThirtyDays() {
        assertThat(TrashTimes.due(DELETED, DELETED)).isFalse();
        assertThat(TrashTimes.due(DELETED, DELETED.plus(MICRO))).isFalse();
        assertThat(TrashTimes.due(DELETED, DELETED.plus(Duration.ofDays(29)))).isFalse();
        assertThat(TrashTimes.due(DELETED, DELETED.plus(Duration.ofDays(30)).minusSeconds(1))).isFalse();
    }

    @Test
    @DisplayName("S7A-AC-31 a check time before the delete time is never due")
    void neverDueBeforeDelete() {
        assertThat(TrashTimes.due(DELETED, DELETED.minus(MICRO))).isFalse();
        assertThat(TrashTimes.due(DELETED, DELETED.minus(THIRTY_DAYS))).isFalse();
        assertThat(TrashTimes.due(DELETED, DELETED.minus(Duration.ofDays(3650)))).isFalse();
    }

    @Test
    @DisplayName("S7A-AC-31 the latest due delete time is the check time minus 30 days")
    void deletedUntil() {
        Instant now = Instant.parse("2026-11-15T05:00:00.123456Z");
        assertThat(TrashTimes.deletedUntil(now)).isEqualTo(DELETED);
        assertThat(TrashTimes.deletedUntil(now)).isEqualTo(now.minus(THIRTY_DAYS));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "2026-11-15T05:00:00.123456Z",
        "2026-03-29T01:00:00Z",
        "2026-11-01T06:00:00Z",
        "1970-01-31T00:00:00Z"
    })
    @DisplayName("S7A-AC-31 deletedUntil and due draw the same line: deleted_at <= deletedUntil(now) is due")
    void deletedUntilSameLineAsDue(String check) {
        Instant now = Instant.parse(check);
        Instant until = TrashTimes.deletedUntil(now);
        assertThat(TrashTimes.due(until, now)).isTrue();
        assertThat(TrashTimes.due(until.minus(MICRO), now)).isTrue();
        assertThat(TrashTimes.due(until.plus(MICRO), now)).isFalse();
        assertThat(TrashTimes.purgeAt(until)).isEqualTo(now);
    }
}
