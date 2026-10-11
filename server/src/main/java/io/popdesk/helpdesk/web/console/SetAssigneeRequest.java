package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;

public record SetAssigneeRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "An `ACTIVE` admin, or an `ACTIVE` agent assigned to the issue's company (ISSUE-7).")
                Long accountId) {}
