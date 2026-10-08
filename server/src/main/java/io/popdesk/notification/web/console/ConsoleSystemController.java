package io.popdesk.notification.web.console;

import io.popdesk.NotImplementedException;
import io.popdesk.Paged;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "System")
@RestController
@RequestMapping("/api/console/system")
class ConsoleSystemController {

    @Operation(operationId = "getSystemStatus", summary = "Outgoing channels",
            description = "For mail and webhook: configured or not, last success, queued and failed rows (S0-AC-81). "
                    + "The console also reads `mail.enabled` to decide whether to offer invitation links (C-10).")
    @ApiResponse(responseCode = "200", description = "The status.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`FORBIDDEN`: admins only.")
    @GetMapping("/status")
    SystemStatus getSystemStatus() {
        throw new NotImplementedException();
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
        throw new NotImplementedException();
    }
}
