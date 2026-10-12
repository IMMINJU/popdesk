package io.popdesk.notification.web.portal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/**
 * Who did what an inbox item is about, as a customer may see it (ANON-1, ADR D-49): the fields of a
 * {@code Person} of the issue endpoints. A type of this module, because notification does not use
 * helpdesk's types.
 */
@Schema(description = "A customer, or the support team. For `SUPPORT_TEAM` `accountId`, `name`, and `photoUpdatedAt` "
        + "are all null.")
public record NotificationActor(
        Kind kind,
        @Schema(nullable = true) Long accountId,
        @Schema(nullable = true) String name,
        @Schema(description = "When the customer's profile photo last changed; null without a photo "
                + "(`getPhoto`). Always null for `SUPPORT_TEAM`.", nullable = true) OffsetDateTime photoUpdatedAt) {

    /** {@code SUPPORT_TEAM}: any agent or admin; the portal shows "Support team" (ADR D-49). */
    public enum Kind {
        CUSTOMER,
        SUPPORT_TEAM
    }
}
