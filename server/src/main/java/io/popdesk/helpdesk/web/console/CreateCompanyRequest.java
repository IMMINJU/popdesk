package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;

public record CreateCompanyRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "1 to 100 characters.") String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "2 to 10 capital letters and digits, unique. Cannot be changed later.") String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "1 to 100 characters.") String legalName) {}
