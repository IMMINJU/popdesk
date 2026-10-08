package io.popdesk.notification.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** An item of the caller's inbox. */
public record Notification(
        long id,
        NotificationKind kind,
        OffsetDateTime createdAt,
        @Schema(description = "When the caller marked it read.", nullable = true) OffsetDateTime readAt) {}
