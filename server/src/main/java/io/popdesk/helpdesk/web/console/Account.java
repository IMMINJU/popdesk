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
                nullable = true) OffsetDateTime photoUpdatedAt,
        @Schema(description = "The account was anonymized (PII-4): `name` and `email` are the "
                + "anonymous values, `status` is `SUSPENDED`, and nothing brings it back (S10A-AC-13).")
                boolean anonymized,
        @Schema(description = "The account is linked to an OIDC provider's account (AUTH-6, S11-AC-26): later "
                + "OIDC sign-ins match it by that link only. `unlinkAccountOidc` removes it.") boolean oidcLinked) {}
