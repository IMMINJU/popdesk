package io.popdesk.ops;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.ops.Prometheus.Names;
import io.popdesk.ops.Prometheus.Sample;
import io.popdesk.ops.Prometheus.Scrape;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/**
 * The values of the cleanup metrics (architecture section 6, OPS-2). {@link MetricsTest} checks that
 * every job has a last run time and a deleted count; here the session cleanup has sessions to end,
 * so the count is not zero, and its last run is the time of the app's clock in seconds, with the part
 * of a second that time has.
 *
 * <p>Other tests of this context sign in too, so the count is at least what this test left.
 */
class CleanupMetricsTest extends BareAppTest {

    @Autowired
    Environment environment;

    @Test
    @DisplayName("S0-AC-78 after the session cleanup ended two sessions, its metrics give the time it ran in seconds and a deleted count of at least two")
    void sessionCleanupValues() {
        Prometheus prometheus = new Prometheus(environment);
        Session admin = fixtures.signInAdmin();
        fixtures.agent(admin);
        Instant due = clock.advance(Duration.ofHours(25).plus(toAFraction()));
        double dueSeconds = due.getEpochSecond() + due.getNano() / 1e9;

        Wait.untilAsserted("the metrics of " + CleanupRecordTest.SESSIONS + " for a run at " + due,
                Duration.ofSeconds(90), () -> {
                    Scrape scrape = prometheus.scrape();
                    double lastRun = ofJob(scrape, Names.CLEANUP_LAST_RUN, "last run time");
                    double deleted = scrape.where(s -> s.name().startsWith(Names.CLEANUP_DELETED_PREFIX)
                                    && CleanupRecordTest.SESSIONS.equals(s.label("job")))
                            .stream().mapToDouble(Sample::value).findFirst()
                            .orElseThrow(() -> new AssertionError("no deleted count of " + CleanupRecordTest.SESSIONS));
                    assertThat(lastRun).as("last run in seconds, for a run at %s (%s)", due, dueSeconds)
                            .isBetween(dueSeconds - 0.0005, dueSeconds + 1);
                    assertThat(deleted).as("sessions the run ended").isGreaterThanOrEqualTo(2);
                });
    }

    /** A part of a second that leaves the clock off a whole second, so the run's time has a fraction to give. */
    private Duration toAFraction() {
        int millis = clock.instant().getNano() / 1_000_000;
        return Duration.ofMillis(millis >= 700 && millis < 800 ? 500 : 250);
    }

    private static double ofJob(Scrape scrape, String name, String what) {
        return scrape.named(name).stream().filter(s -> CleanupRecordTest.SESSIONS.equals(s.label("job")))
                .mapToDouble(Sample::value).findFirst()
                .orElseThrow(() -> new AssertionError("no " + what + " of " + CleanupRecordTest.SESSIONS));
    }
}
