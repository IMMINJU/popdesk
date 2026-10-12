package io.popdesk.notification.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/**
 * Who did what an inbox item is about, as staff see it: the fields of a {@code Person} of the issue
 * endpoints. A type of this module, because notification does not use helpdesk's types.
 */
public record NotificationActor(
        long accountId,
        String name,
        Role role,
        @Schema(description = "When the profile photo last changed; null without a photo (`getPhoto`).",
                nullable = true) OffsetDateTime photoUpdatedAt) {

    /** The account roles, by the names helpdesk gives them. */
    public enum Role {
        CUSTOMER,
        AGENT,
        ADMIN
    }
}
