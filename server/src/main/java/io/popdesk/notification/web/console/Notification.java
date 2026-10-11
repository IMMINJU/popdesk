package io.popdesk.notification.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** An item of the caller's inbox. */
public record Notification(
        long id,
        NotificationKind kind,
        @Schema(description = "The company a `MEMBERSHIP_REQUESTED` item is about; null for the other kinds.",
                nullable = true) String companyCode,
        OffsetDateTime createdAt,
        @Schema(description = "When the caller marked it read.", nullable = true) OffsetDateTime readAt) {}
