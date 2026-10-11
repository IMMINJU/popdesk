package io.popdesk.helpdesk.web.console;

import io.popdesk.helpdesk.AccountStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/** A customer who belongs to a company, or an agent assigned to it. {@code INVITED}: not accepted yet. */
public record CompanyMember(
        long accountId,
        String name,
        String email,
        AccountStatus status,
        @Schema(description = "Whether the account has a profile photo (`getPhoto`).") boolean hasPhoto) {}
