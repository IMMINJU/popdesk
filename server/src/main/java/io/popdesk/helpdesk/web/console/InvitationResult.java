package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;

/** The invited account, and the link when there is no SMTP server to email it (INVITE-6). */
public record InvitationResult(
        long accountId,
        @Schema(description = "The invitation link, only when SMTP is not configured; shown once. `null` when the "
                + "invitation email is sent.", nullable = true) String inviteLink) {}
