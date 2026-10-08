package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;

/** A new invitation link. The previous link no longer works. */
public record InvitationLink(
        @Schema(description = "Shown once; the token is in the fragment after `#`.") String inviteLink) {}
