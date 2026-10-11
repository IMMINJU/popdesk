package io.popdesk.notification.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** An outbox row that stopped retrying (S0-AC-68, S0-AC-81). */
public record FailedDelivery(
        long id,
        DeliveryChannel channel,
        OffsetDateTime createdAt,
        @Schema(description = "Kind of the last error, for example `CONNECTION_REFUSED`; never the error text.")
                String lastErrorKind,
        @Schema(description = "The receiving account of an email; null for a webhook.", nullable = true)
                Long recipientAccountId,
        @Schema(description = "The audit entry of an outside copy (`AUDIT_FORWARD`); null for the other channels.",
                nullable = true)
                Long auditEntryId) {}
