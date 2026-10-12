package io.popdesk.notification.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** One cleanup job of the system status (S4B-AC-30, OPS-2). */
public record CleanupJobStatus(
        @Schema(description = "The job's name, as in the metrics label `job`, for example `session-cleanup`.")
                String name,
        @Schema(description = "When the job last ran; null when it never ran.", nullable = true)
                OffsetDateTime lastRunAt,
        @Schema(description = "How many rows that run deleted; null when the job never ran.", nullable = true)
                Long deleted) {}
