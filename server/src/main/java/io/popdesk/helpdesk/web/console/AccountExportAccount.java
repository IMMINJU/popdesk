package io.popdesk.helpdesk.web.console;

import io.popdesk.helpdesk.AccountStatus;
import io.popdesk.helpdesk.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** The account part of an {@link AccountExport}. */
public record AccountExportAccount(
        long id,
        @Schema(description = "`deleted-{id}@invalid` for an anonymized account.") String email,
        @Schema(description = "The anonymous name for an anonymized account.") String name,
        Role role,
        AccountStatus status,
        @Schema(description = "The account's language, `en` or `ko`.") String locale,
        OffsetDateTime createdAt,
        boolean anonymized) {}
