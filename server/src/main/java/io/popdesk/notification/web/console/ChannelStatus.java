package io.popdesk.notification.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** One outgoing channel. Never carries the SMTP password or the webhook URL (S0-AC-81). */
public record ChannelStatus(
        @Schema(description = "Configured.") boolean enabled,
        @Schema(nullable = true) OffsetDateTime lastSuccessAt,
        @Schema(description = "Rows waiting to be sent.") long queued,
        @Schema(description = "Rows that stopped retrying.") long failed) {}
