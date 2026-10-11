package io.popdesk.helpdesk.web.portal;

import io.popdesk.helpdesk.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** The signed-in customer (S1A-AC-19). */
public record Me(
        long id,
        String email,
        String name,
        @Schema(description = "Always `CUSTOMER` on the portal.") Role role,
        @Schema(description = "Language of the screens and of the account's email.", allowableValues = {"ko", "en"})
                String locale,
        @Schema(description = "Whether the account has a profile photo (`getPhoto`).") boolean hasPhoto,
        @Schema(description = "The companies the customer belongs to, whatever their status, in code order.")
                List<Company> companies) {}
