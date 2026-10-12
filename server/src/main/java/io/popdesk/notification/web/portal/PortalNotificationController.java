package io.popdesk.notification.web.portal;

import io.popdesk.Paged;
import io.popdesk.notification.InboxFacts.Actor;
import io.popdesk.notification.InboxFacts.ActorKind;
import io.popdesk.notification.InboxFacts.Reader;
import io.popdesk.notification.ops.Inbox;
import io.popdesk.notification.ops.Inbox.Item;
import io.popdesk.notification.web.Caller;
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
@RequestMapping("/api/portal/notifications")
class PortalNotificationController {

    private final Inbox inbox;

    PortalNotificationController(Inbox inbox) {
        this.inbox = inbox;
    }

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
        return inbox.list(Caller.accountId(), Reader.CUSTOMER, page, size)
                .map(PortalNotificationController::notification);
    }

    @Operation(operationId = "countUnreadNotifications", summary = "Unread items in the caller's inbox",
            description = "Counts what `listNotifications` would list and the caller has not read (S4-AC-43).")
    @ApiResponse(responseCode = "200", description = "The count.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @GetMapping("/unread-count")
    UnreadCount countUnreadNotifications() {
        return new UnreadCount(inbox.unread(Caller.accountId(), Reader.CUSTOMER));
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
        inbox.markRead(Caller.accountId(), id);
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
        inbox.markAllRead(Caller.accountId());
    }

    private static Notification notification(Item item) {
        return new Notification(item.id(), NotificationKind.valueOf(item.kind()),
                new NotificationIssue(item.issue().key(), item.issue().title()), item.companyCode(),
                item.commentId(), actor(item.actor()), utc(item.createdAt()), utc(item.readAt()));
    }

    /**
     * Who did it, as helpdesk gave it for a customer: staff come as the support team already, without
     * name or ID (ADR D-49). So does an item whose actor is not an account.
     */
    private static NotificationActor actor(Actor actor) {
        return actor == null || actor.kind() != ActorKind.CUSTOMER
                ? new NotificationActor(NotificationActor.Kind.SUPPORT_TEAM, null, null, null)
                : new NotificationActor(NotificationActor.Kind.CUSTOMER, actor.accountId(), actor.name(),
                        utc(actor.photoUpdatedAt()));
    }

    private static OffsetDateTime utc(Instant at) {
        return at == null ? null : at.atOffset(ZoneOffset.UTC);
    }
}
