package io.popdesk.notification.ops;

import io.popdesk.InstanceLock;
import io.popdesk.notification.CleanupJob;
import io.popdesk.notification.CleanupRecords;
import io.popdesk.notification.outbox.OutboxMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The scheduled jobs (tasks.md section 6): a thread that wakes every second of real time, and decides by
 * the app clock what is due. Each round it runs the {@link CleanupJob}s whose interval has passed since
 * their last recorded run and records them after they deleted (S0-AC-82), reviews the operations alerts
 * (S0-AC-80), and reads the counts behind the metrics again. Jobs and alerts pause while this instance
 * does not hold the single-instance lock (S0-AC-11).
 */
@Component
class Housekeeping implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(Housekeeping.class);

    /** How long a job that failed waits, in app time, before it is tried again. */
    static final Duration RETRY_FAILED_JOB = Duration.ofMinutes(1);

    private final List<CleanupJob> jobs;
    private final CleanupRecords records;
    private final OpsAlertService alerts;
    private final OutboxMetrics outboxMetrics;
    private final OpsMetrics opsMetrics;
    private final InstanceLock lock;
    private final Clock clock;
    private final Map<String, Instant> failedAt = new ConcurrentHashMap<>();
    private volatile ScheduledExecutorService scheduler;

    Housekeeping(List<CleanupJob> jobs, CleanupRecords records, OpsAlertService alerts, OutboxMetrics outboxMetrics,
            OpsMetrics opsMetrics, InstanceLock lock, Clock clock) {
        this.jobs = jobs;
        this.records = records;
        this.alerts = alerts;
        this.outboxMetrics = outboxMetrics;
        this.opsMetrics = opsMetrics;
        this.lock = lock;
        this.clock = clock;
    }

    @Override
    public void start() {
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("popdesk-housekeeping").factory());
        s.scheduleWithFixedDelay(this::round, 0, 1, TimeUnit.SECONDS);
        scheduler = s;
    }

    @Override
    public void stop() {
        ScheduledExecutorService s = scheduler;
        scheduler = null;
        if (s != null) {
            s.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }

    private void round() {
        step("metrics", () -> {
            outboxMetrics.refresh();
            opsMetrics.refresh();
        });
        if (!lock.held()) {
            return;
        }
        for (CleanupJob job : jobs) {
            step(job.name(), () -> runIfDue(job));
        }
        step("operations alerts", alerts::review);
    }

    private void runIfDue(CleanupJob job) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Optional<Instant> last = records.lastRun(job.name());
        Instant failed = failedAt.get(job.name());
        if (last.isPresent() && now.isBefore(last.get().plus(job.interval()))
                || failed != null && now.isBefore(failed.plus(RETRY_FAILED_JOB))) {
            return;
        }
        try {
            long deleted = job.run(now);
            records.record(job.name(), now, deleted);
            failedAt.remove(job.name());
        } catch (RuntimeException e) {
            failedAt.put(job.name(), now);
            throw e;
        }
    }

    /** One part of a round; a failure is logged by its kind only and does not stop the others. */
    private static void step(String what, Runnable part) {
        try {
            part.run();
        } catch (RuntimeException e) {
            log.warn("Scheduled work '{}' failed ({}); it is tried again later.", what, e.getClass().getSimpleName());
        }
    }
}
