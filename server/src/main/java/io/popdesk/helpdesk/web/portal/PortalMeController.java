package io.popdesk.helpdesk.web.portal;

import io.popdesk.helpdesk.account.CurrentAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Session")
@RestController
class PortalMeController {

    @Operation(operationId = "getMe", summary = "The signed-in customer",
            description = "Also tells whether the session is still valid.")
    @ApiResponse(responseCode = "200", description = "Signed in.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`: no session, or it ended (S0-AC-25).")
    @GetMapping("/api/portal/me")
    Me getMe() {
        CurrentAccount account = CurrentAccount.get();
        return new Me(account.id(), account.email(), account.name());
    }
}
