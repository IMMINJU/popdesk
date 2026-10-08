package io.popdesk.helpdesk.web.publicapi;

import io.swagger.v3.oas.annotations.media.Schema;

public record SignInRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Leading and trailing whitespace and case are ignored.") String email,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String password) {}
