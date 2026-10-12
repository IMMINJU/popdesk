package io.popdesk.notification.web.portal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** An item of the caller's inbox. Every item of a customer is about an issue the customer can see. */
public record Notification(
        long id,
        NotificationKind kind,
        NotificationIssue issue,
        @Schema(description = "The company of the issue.") String companyCode,
        @Schema(description = "The comment the item is about; null when it is about no comment.", nullable = true)
                Long commentId,
        NotificationActor actor,
        OffsetDateTime createdAt,
        @Schema(description = "When the caller marked it read.", nullable = true) OffsetDateTime readAt) {}
