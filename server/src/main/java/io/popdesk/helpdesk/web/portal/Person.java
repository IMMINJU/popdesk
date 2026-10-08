package io.popdesk.helpdesk.web.portal;

import io.swagger.v3.oas.annotations.media.Schema;

/** Who wrote or uploaded something, as a customer may see it (ANON-1, ADR D-49). */
@Schema(description = "A customer, or the support team. For `SUPPORT_TEAM` both `accountId` and `name` are null.")
public record Person(PersonKind kind, @Schema(nullable = true) Long accountId, @Schema(nullable = true) String name) {}
