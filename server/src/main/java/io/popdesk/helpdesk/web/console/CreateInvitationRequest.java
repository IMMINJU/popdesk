package io.popdesk.helpdesk.web.console;

import io.popdesk.helpdesk.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record CreateInvitationRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Up to 100 characters.") String email,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "1 to 50 characters.") String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Role role,
        @Schema(description = "Codes of the companies a customer joins. At least one for `CUSTOMER`; left out or "
                + "empty for staff.") List<String> companyCodes) {}
