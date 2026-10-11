package io.popdesk.helpdesk.web.console;

import io.popdesk.helpdesk.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/** An account that has not accepted its invitation yet (S0-AC-40). */
public record PendingInvitation(
        long accountId,
        String email,
        String name,
        Role role,
        @Schema(description = "The companies a customer was invited to; `[]` for staff.") List<CompanyRef> companies,
        OffsetDateTime invitedAt,
        OffsetDateTime expiresAt,
        @Schema(description = "The current link is past its 7 days.") boolean expired,
        @Schema(description = "An admin cancelled the invitation; the link does not work until it is sent again "
                + "(S1A-AC-42).") boolean cancelled) {}
