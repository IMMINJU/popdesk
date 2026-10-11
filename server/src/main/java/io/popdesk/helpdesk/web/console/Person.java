package io.popdesk.helpdesk.web.console;

import io.popdesk.helpdesk.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** Who wrote, reported, or uploaded something, as staff see it. */
public record Person(
        long accountId,
        String name,
        Role role,
        @Schema(description = "When the profile photo last changed; null without a photo (`getPhoto`).",
                nullable = true) OffsetDateTime photoUpdatedAt) {}
