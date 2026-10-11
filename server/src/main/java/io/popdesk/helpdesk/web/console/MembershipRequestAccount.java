package io.popdesk.helpdesk.web.console;

import io.popdesk.helpdesk.AccountStatus;
import io.popdesk.helpdesk.Role;
import io.swagger.v3.oas.annotations.media.Schema;

/** The account a membership request is about, as it is now. */
public record MembershipRequestAccount(
        long accountId,
        String name,
        @Schema(description = "Only a `CUSTOMER` can be approved (S2-AC-36).") Role role,
        AccountStatus status,
        @Schema(description = "The account never accepted its invitation and the invitation is cancelled "
                + "(S1A-AC-42).") boolean invitationCancelled) {}
