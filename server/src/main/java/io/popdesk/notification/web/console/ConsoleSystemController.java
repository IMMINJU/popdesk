package io.popdesk.notification.web.console;

import io.popdesk.Paged;
import io.popdesk.notification.outbox.DeliveryStatus;
import io.popdesk.notification.outbox.OutboxRepository.FailedRow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "System")
@RestController
@RequestMapping("/api/console/system")
class ConsoleSystemController {

    private final DeliveryStatus status;

    ConsoleSystemController(DeliveryStatus status) {
        this.status = status;
    }

    @Operation(operationId = "getSystemStatus", summary = "Outgoing channels",
            description = "For mail, webhook, and the outside copy of the audit log (`auditForward`): configured or "
                    + "not, last success, queued and failed rows (S0-AC-81, S1B-AC-33). "
                    + "The console also reads `mail.enabled` to decide whether to offer invitation links (C-10).")
    @ApiResponse(responseCode = "200", description = "The status.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`FORBIDDEN`: admins only.")
    @GetMapping("/status")
    SystemStatus getSystemStatus() {
        return new SystemStatus(channel(status.mail()), channel(status.webhook()), channel(status.auditForward()));
    }

    @Operation(operationId = "listFailedDeliveries", summary = "Outbox rows that stopped retrying",
            description = "Newest first (S0-AC-81). Read only in the first slice.")
    @ApiResponse(responseCode = "200", description = "One page.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `page` or `size` out of range (`OUT_OF_RANGE`).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`FORBIDDEN`: admins only.")
    @GetMapping("/failed-deliveries")
    Paged<FailedDelivery> listFailedDeliveries(
            @Parameter(description = "Page number, from 1.") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "1 to 100.") @RequestParam(defaultValue = "20") int size) {
        return status.failed(page, size).map(ConsoleSystemController::failedDelivery);
    }

    private static ChannelStatus channel(DeliveryStatus.Channel c) {
        return new ChannelStatus(c.enabled(), utc(c.lastSuccessAt()), c.queued(), c.failed());
    }

    private static FailedDelivery failedDelivery(FailedRow row) {
        return new FailedDelivery(row.id(), DeliveryChannel.valueOf(row.channel()), utc(row.createdAt()),
                row.lastErrorKind(), row.recipientAccountId(), row.auditId());
    }

    private static OffsetDateTime utc(Instant at) {
        return at == null ? null : at.atOffset(ZoneOffset.UTC);
    }
}
