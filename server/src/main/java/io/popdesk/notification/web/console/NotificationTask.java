package io.popdesk.notification.web.console;

import io.swagger.v3.oas.annotations.media.Schema;

/** The task an inbox item is about (S6B-AC-33). */
public record NotificationTask(
        long id,
        @Schema(description = "The title as it is now, not as it was when the item was made.") String title) {}
