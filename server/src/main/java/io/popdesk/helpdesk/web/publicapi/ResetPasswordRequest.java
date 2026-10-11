package io.popdesk.helpdesk.web.publicapi;

import io.swagger.v3.oas.annotations.media.Schema;

public record ResetPasswordRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The token from the reset link's fragment (after `#`). Sent only in the body.")
                String token,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "10 to 72 characters, not only whitespace, not the same as the email (AUTH-5).")
                String password) {}
