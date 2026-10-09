package io.popdesk.notification.ops;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.popdesk.notification.ops.OpsRepository.CleanupRow;
import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Metrics of the cleanup record, the database, and the data volume (S0-AC-78, architecture.md section
 * 6): {@code popdesk_cleanup_last_run_seconds} and {@code popdesk_cleanup_deleted} with label
 * {@code job}, {@code popdesk_db_size_bytes}, and {@code popdesk_data_volume_free_bytes}. The cleanup
 * gauges hold the record as of the last {@link #refresh()}; a job gets its gauges once it has a row.
 */
@Component
public class OpsMetrics {

    /** How long a measured database size is used before it is measured again. */
    static final Duration DB_SIZE_FOR = Duration.ofSeconds(30);

    private static final String DEFAULT_STORAGE_DIR = "/data/attachments";

    private final OpsRepository ops;
    private final MeterRegistry registry;
    private final File dataVolume;
    private final Map<String, CleanupRow> cleanups = new ConcurrentHashMap<>();
    private volatile long dbSize = -1;
    private volatile long dbSizeAtNanos;

    OpsMetrics(OpsRepository ops, MeterRegistry registry, Environment environment) {
        this.ops = ops;
        this.registry = registry;
        String dir = environment.getProperty("POPDESK_STORAGE_DIR");
        this.dataVolume = new File(dir == null || dir.isBlank() ? DEFAULT_STORAGE_DIR : dir.strip());
        Gauge.builder("popdesk.db.size", this, OpsMetrics::databaseSize)
                .description("Size of the database")
                .baseUnit("bytes")
                .register(registry);
        Gauge.builder("popdesk.data.volume.free", this, OpsMetrics::volumeFree)
                .description("Free space on the volume of POPDESK_STORAGE_DIR")
                .baseUnit("bytes")
                .register(registry);
    }

    /** Reads the cleanup record again, and adds gauges for a job that has its first row. */
    public void refresh() {
        for (CleanupRow row : ops.listCleanups()) {
            if (cleanups.put(row.job(), row) == null) {
                String job = row.job();
                Gauge.builder("popdesk.cleanup.last.run", this, m -> m.lastRun(job))
                        .description("When the cleanup job last ran, Unix time")
                        .baseUnit("seconds")
                        .tag("job", job)
                        .register(registry);
                Gauge.builder("popdesk.cleanup.deleted", this, m -> m.deleted(job))
                        .description("Rows the cleanup job deleted in its last run")
                        .tag("job", job)
                        .register(registry);
            }
        }
    }

    private double lastRun(String job) {
        Instant at = cleanups.get(job).lastRunAt();
        return at.getEpochSecond() + at.getNano() / 1e9;
    }

    private double deleted(String job) {
        return cleanups.get(job).deletedCount();
    }

    /** Measured at most every 30 seconds, when the metrics are read. */
    private double databaseSize() {
        long now = System.nanoTime();
        if (dbSize < 0 || now - dbSizeAtNanos > DB_SIZE_FOR.toNanos()) {
            dbSize = ops.databaseSize();
            dbSizeAtNanos = now;
        }
        return dbSize;
    }

    /** Free space where attachment files go; the nearest folder that exists when that one does not yet. */
    private double volumeFree() {
        File dir = dataVolume.getAbsoluteFile();
        while (dir != null && !dir.exists()) {
            dir = dir.getParentFile();
        }
        return dir == null ? Double.NaN : dir.getUsableSpace();
    }
}
