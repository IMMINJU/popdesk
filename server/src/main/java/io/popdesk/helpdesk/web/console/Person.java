package io.popdesk.helpdesk.web.console;

import io.popdesk.helpdesk.Role;
import io.swagger.v3.oas.annotations.media.Schema;

/** Who wrote, reported, or uploaded something, as staff see it. */
public record Person(
        long accountId,
        String name,
        Role role,
        @Schema(description = "Whether the account has a profile photo (`getPhoto`).") boolean hasPhoto) {}
