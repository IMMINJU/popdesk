package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;

/** The outcome of an admin's password reset: the link when there is no SMTP server to email it (S1A-AC-37). */
public record PasswordResetLink(
        @Schema(description = "The reset link, only when SMTP is not configured; shown once, the token is in the "
                + "fragment after `#`. `null` when the reset email is sent.", nullable = true) String resetLink) {}
