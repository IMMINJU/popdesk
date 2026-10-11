package io.popdesk.helpdesk.web.portal;

import io.swagger.v3.oas.annotations.media.Schema;

/** Who wrote or uploaded something, as a customer may see it (ANON-1, ADR D-49). */
@Schema(description = "A customer, or the support team. For `SUPPORT_TEAM` `accountId`, `name`, and `hasPhoto` are "
        + "all null.")
public record Person(
        PersonKind kind,
        @Schema(nullable = true) Long accountId,
        @Schema(nullable = true) String name,
        @Schema(description = "Whether the customer has a profile photo (`getPhoto`). Always null for "
                + "`SUPPORT_TEAM`: the portal never shows a staff photo (S1A-AC-26).", nullable = true)
                Boolean hasPhoto) {}
