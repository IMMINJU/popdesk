package io.popdesk.helpdesk.web.console;

import io.popdesk.helpdesk.AccountStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** A customer who belongs to a company, or an agent assigned to it. {@code INVITED}: not accepted yet. */
public record CompanyMember(
        long accountId,
        String name,
        String email,
        AccountStatus status,
        @Schema(description = "When the profile photo last changed; null without a photo (`getPhoto`).",
                nullable = true) OffsetDateTime photoUpdatedAt,
        @Schema(description = "The customer is a manager of this company (MGR-1). Always `false` for an agent.")
                boolean manager,
        @Schema(description = "The account was anonymized (PII-4): `name` and `email` are the anonymous values and "
                + "`status` is `SUSPENDED` (B2-AC-01). `false` for every other account.") boolean anonymized) {}
