package io.popdesk.notification.ops;

import io.popdesk.notification.CleanupJob;
import io.popdesk.notification.ops.OpsRepository.CleanupRow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The cleanup jobs as the system status lists them (S4B-AC-30, OPS-2): every {@link CleanupJob} of the
 * app and every job with a row in the cleanup record, which a job that runs on its own writes too, in
 * name order.
 */
@Service
public class CleanupJobs {

    /** One job. {@code lastRunAt} and {@code deleted} are both null for a job that never ran. */
    public record Job(String name, Instant lastRunAt, Long deleted) {}

    private final List<CleanupJob> jobs;
    private final OpsRepository ops;

    CleanupJobs(List<CleanupJob> jobs, OpsRepository ops) {
        this.jobs = jobs;
        this.ops = ops;
    }

    @Transactional(readOnly = true)
    public List<Job> list() {
        Map<String, Job> byName = new TreeMap<>();
        for (CleanupJob job : jobs) {
            byName.put(job.name(), new Job(job.name(), null, null));
        }
        for (CleanupRow row : ops.listCleanups()) {
            byName.put(row.job(), new Job(row.job(), row.lastRunAt(), row.deletedCount()));
        }
        return List.copyOf(byName.values());
    }
}
