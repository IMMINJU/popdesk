package io.popdesk.helpdesk.web.console;

import io.popdesk.helpdesk.Role;
import io.swagger.v3.oas.annotations.media.Schema;

public record ChangeRoleRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "`AGENT` or `ADMIN`.") Role role) {}
