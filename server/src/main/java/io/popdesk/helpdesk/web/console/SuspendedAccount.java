package io.popdesk.helpdesk.web.console;

import io.popdesk.helpdesk.AccountStatus;
import io.popdesk.helpdesk.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** The answer to a suspension: the fields of {@link Account}, and what the suspension cleared (S3-AC-69). */
public record SuspendedAccount(
        long id,
        String email,
        String name,
        Role role,
        AccountStatus status,
        OffsetDateTime createdAt,
        @Schema(description = "When the profile photo last changed; null without a photo (`getPhoto`).",
                nullable = true) OffsetDateTime photoUpdatedAt,
        ClearedAssignments cleared) {}
