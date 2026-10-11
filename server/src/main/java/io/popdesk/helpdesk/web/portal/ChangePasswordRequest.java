package io.popdesk.helpdesk.web.portal;

import io.swagger.v3.oas.annotations.media.Schema;

public record ChangePasswordRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String currentPassword,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "10 to 72 characters, not only whitespace, not the same as the email (AUTH-5).")
                String newPassword) {}
