package io.popdesk.helpdesk.web.console;

import io.popdesk.helpdesk.AccountStatus;
import io.popdesk.helpdesk.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/**
 * The answer to a suspension or an anonymization: the fields of {@link Account}, and what the request cleared
 * (S3-AC-69, S10A-AC-08).
 */
public record SuspendedAccount(
        long id,
        String email,
        String name,
        Role role,
        AccountStatus status,
        OffsetDateTime createdAt,
        @Schema(description = "When the profile photo last changed; null without a photo (`getPhoto`).",
                nullable = true) OffsetDateTime photoUpdatedAt,
        @Schema(description = "The account was anonymized (PII-4): `name` and `email` are the "
                + "anonymous values, `status` is `SUSPENDED`, and nothing brings it back (S10A-AC-13).")
                boolean anonymized,
        ClearedAssignments cleared) {}
