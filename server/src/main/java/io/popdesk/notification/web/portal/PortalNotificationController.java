package io.popdesk.notification.web.portal;

import io.popdesk.NotImplementedException;
import io.popdesk.Paged;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@RequestMapping("/api/portal/notifications")
class PortalNotificationController {

    @Operation(operationId = "listNotifications", summary = "The caller's inbox",
            description = "Own items only, newest first (S4-AC-39, INBOX-3). An item about an issue the caller "
                    + "cannot see now is left out, and is back when the caller can see the issue again (S4-AC-42). "
                    + "What staff did has actor `SUPPORT_TEAM` without name or ID (ADR D-49, S4-AC-41).")
    @ApiResponse(responseCode = "200", description = "One page.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `page` or `size` out of range (`OUT_OF_RANGE`).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @GetMapping
    Paged<Notification> listNotifications(
            @Parameter(description = "Page number, from 1.") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "1 to 100.") @RequestParam(defaultValue = "20") int size) {
        throw new NotImplementedException();
    }

    @Operation(operationId = "countUnreadNotifications", summary = "Unread items in the caller's inbox",
            description = "Counts what `listNotifications` would list and the caller has not read (S4-AC-43).")
    @ApiResponse(responseCode = "200", description = "The count.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @GetMapping("/unread-count")
    UnreadCount countUnreadNotifications() {
        throw new NotImplementedException();
    }

    @Operation(operationId = "markNotificationRead", summary = "Mark an item read",
            description = "Marking a read item again is 204 too and keeps the first time (S4-AC-44).")
    @ApiResponse(responseCode = "204", description = "Read.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: no such item in the caller's inbox.")
    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void markNotificationRead(@PathVariable long id) {
        throw new NotImplementedException();
    }

    @Operation(operationId = "markAllNotificationsRead", summary = "Mark every item read",
            description = "Every unread item of the caller, those of issues the caller cannot see now included; "
                    + "nobody else's (S4-AC-45). 204 too when nothing was unread.")
    @ApiResponse(responseCode = "204", description = "Read.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`CSRF_TOKEN_INVALID`.")
    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void markAllNotificationsRead() {
        throw new NotImplementedException();
    }
}
