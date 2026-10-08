package io.popdesk.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The app's clock in tests: stands still at {@link #START} and moves only when a test calls
 * {@link #advance}. It never goes back, because rows written earlier keep their times.
 *
 * <p>One clock per test context, shared by every test class that uses the context. Do not assume
 * the time is {@link #START} when your test begins; read {@link #instant()} and work with
 * differences.
 */
public final class TestClock extends Clock {

    /** The time a fresh context starts at. */
    public static final Instant START = Instant.parse("2026-10-08T03:15:00Z");

    private final AtomicReference<Instant> now;

    public TestClock() {
        this(new AtomicReference<>(START));
    }

    private TestClock(AtomicReference<Instant> now) {
        this.now = now;
    }

    /** Moves the clock forward and returns the new time. */
    public Instant advance(Duration duration) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException("The test clock only moves forward: " + duration);
        }
        return now.updateAndGet(t -> t.plus(duration));
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    /** The same moving time, seen in another zone. */
    @Override
    public Clock withZone(ZoneId zone) {
        TestClock shared = new TestClock(now);
        return zone.equals(ZoneOffset.UTC) ? shared : new Clock() {
            @Override
            public ZoneId getZone() {
                return zone;
            }

            @Override
            public Clock withZone(ZoneId other) {
                return shared.withZone(other);
            }

            @Override
            public Instant instant() {
                return shared.instant();
            }
        };
    }
}
