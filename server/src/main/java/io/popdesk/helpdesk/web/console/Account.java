package io.popdesk.helpdesk.web.console;

import io.popdesk.helpdesk.AccountStatus;
import io.popdesk.helpdesk.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** A row of the account list (C-10). */
public record Account(
        long id,
        String email,
        String name,
        Role role,
        AccountStatus status,
        OffsetDateTime createdAt,
        @Schema(description = "When the profile photo last changed; null without a photo (`getPhoto`).",
                nullable = true) OffsetDateTime photoUpdatedAt) {}
