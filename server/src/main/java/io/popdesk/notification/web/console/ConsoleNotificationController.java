package io.popdesk.notification.web.console;

import io.popdesk.Paged;
import io.popdesk.notification.ops.Inbox;
import io.popdesk.notification.ops.OpsRepository.InboxRow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Notifications")
@RestController
@RequestMapping("/api/console/notifications")
class ConsoleNotificationController {

    private final Inbox inbox;

    ConsoleNotificationController(Inbox inbox) {
        this.inbox = inbox;
    }

    @Operation(operationId = "listNotifications", summary = "The caller's inbox",
            description = "Own items only, newest first (S0-AC-80, INBOX-3).")
    @ApiResponse(responseCode = "200", description = "One page.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `page` or `size` out of range (`OUT_OF_RANGE`).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @GetMapping
    Paged<Notification> listNotifications(
            @Parameter(description = "Page number, from 1.") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "1 to 100.") @RequestParam(defaultValue = "20") int size) {
        return inbox.list(Caller.accountId(), page, size).map(ConsoleNotificationController::notification);
    }

    @Operation(operationId = "countUnreadNotifications", summary = "Unread items in the caller's inbox")
    @ApiResponse(responseCode = "200", description = "The count.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @GetMapping("/unread-count")
    UnreadCount countUnreadNotifications() {
        return new UnreadCount(inbox.unread(Caller.accountId()));
    }

    @Operation(operationId = "markNotificationRead", summary = "Mark an item read",
            description = "Marking a read item again is 204 too.")
    @ApiResponse(responseCode = "204", description = "Read.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: no such item in the caller's inbox.")
    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void markNotificationRead(@PathVariable long id) {
        inbox.markRead(Caller.accountId(), id);
    }

    private static Notification notification(InboxRow row) {
        return new Notification(row.id(), NotificationKind.valueOf(row.kind()), utc(row.createdAt()), utc(row.readAt()));
    }

    private static OffsetDateTime utc(Instant at) {
        return at == null ? null : at.atOffset(ZoneOffset.UTC);
    }
}
