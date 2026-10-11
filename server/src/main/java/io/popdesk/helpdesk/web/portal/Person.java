package io.popdesk.helpdesk.web.portal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** Who wrote or uploaded something, as a customer may see it (ANON-1, ADR D-49). */
@Schema(description = "A customer, or the support team. For `SUPPORT_TEAM` `accountId`, `name`, and `photoUpdatedAt` "
        + "are all null.")
public record Person(
        PersonKind kind,
        @Schema(nullable = true) Long accountId,
        @Schema(nullable = true) String name,
        @Schema(description = "When the customer's profile photo last changed; null without a photo "
                + "(`getPhoto`). Always null for `SUPPORT_TEAM`: the portal never shows a staff photo "
                + "(S1A-AC-26).", nullable = true) OffsetDateTime photoUpdatedAt) {}
