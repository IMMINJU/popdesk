package io.popdesk.helpdesk.web.console;

import io.popdesk.NotImplementedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Session")
@RestController
class ConsoleMeController {

    @Operation(operationId = "getMe", summary = "The signed-in agent or admin",
            description = "Also tells whether the session is still valid.")
    @ApiResponse(responseCode = "200", description = "Signed in.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`: no session, or it ended (S0-AC-25).")
    @GetMapping("/api/console/me")
    Me getMe() {
        throw new NotImplementedException();
    }
}
