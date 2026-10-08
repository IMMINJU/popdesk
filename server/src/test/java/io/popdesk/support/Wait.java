package io.popdesk.support;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.awaitility.core.ThrowingRunnable;

/**
 * Waiting for something the app does in the background (a send loop, a scheduled job), without a
 * fixed sleep. Checks the condition again and again until it holds or the time runs out.
 *
 * <p>Use these instead of Awaitility directly: Awaitility ends a timeout with its own exception,
 * which the red check (C-6) reads as an error, not as a failed assertion. These throw
 * {@link AssertionError}.
 */
public final class Wait {

    /** Long enough for a send loop that runs every second to pick up a row and deliver it. */
    public static final Duration DEFAULT = Duration.ofSeconds(15);

    private static final Duration POLL = Duration.ofMillis(100);

    private Wait() {}

    /** Waits until {@code value} returns something other than null, and returns it. */
    public static <T> T until(String what, Supplier<T> value) {
        return until(what, DEFAULT, value);
    }

    public static <T> T until(String what, Duration atMost, Supplier<T> value) {
        try {
            return Awaitility.await(what).atMost(atMost).pollInterval(POLL).pollInSameThread()
                    .until(value::get, Objects::nonNull);
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("Timed out after " + atMost + " waiting for " + what, e);
        }
    }

    /** Waits until {@code check} passes (throws nothing). The last failure is the cause. */
    public static void untilAsserted(String what, ThrowingRunnable check) {
        untilAsserted(what, DEFAULT, check);
    }

    public static void untilAsserted(String what, Duration atMost, ThrowingRunnable check) {
        try {
            Awaitility.await(what).atMost(atMost).pollInterval(POLL).pollInSameThread().untilAsserted(check);
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("Timed out after " + atMost + " waiting for " + what, e);
        }
    }

    /**
     * Checks that {@code condition} stays true for the whole of {@code period}, for "nothing is sent"
     * cases. Fails as soon as it turns false.
     */
    public static void holds(String what, Duration period, BooleanSupplier condition) {
        try {
            Awaitility.await(what).during(period).atMost(period.plusSeconds(5)).pollInterval(POLL)
                    .pollInSameThread().until(condition::getAsBoolean);
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("Expected to hold for " + period + ": " + what, e);
        }
    }
}
