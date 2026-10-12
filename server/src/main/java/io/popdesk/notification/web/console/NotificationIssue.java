package io.popdesk.notification.web.console;

import io.swagger.v3.oas.annotations.media.Schema;

/** The issue an inbox item is about. */
public record NotificationIssue(
        String key,
        @Schema(description = "The title as it is now, not as it was when the item was made (INBOX-1).")
                String title) {}
