package io.popdesk.helpdesk.web.console;

import io.popdesk.helpdesk.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** The signed-in agent or admin (S1A-AC-19). */
public record Me(
        long id,
        String email,
        String name,
        Role role,
        @Schema(description = "Language of the screens and of the account's email.", allowableValues = {"ko", "en"})
                String locale,
        @Schema(description = "Whether the account has a profile photo (`getPhoto`).") boolean hasPhoto,
        @Schema(description = "The companies an agent is assigned to, in code order; `[]` for an admin, who sees "
                + "every company.") List<CompanyRef> companies) {}
