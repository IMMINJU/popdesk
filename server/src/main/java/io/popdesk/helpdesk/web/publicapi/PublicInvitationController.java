package io.popdesk.helpdesk.web.publicapi;

import io.popdesk.NotImplementedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Invitations")
@RestController
@RequestMapping("/api/public/invitations")
class PublicInvitationController {

    @Operation(operationId = "acceptInvitation", summary = "Accept an invitation",
            description = "Sets the password, makes the account `ACTIVE`, and signs it in to its app (S0-AC-36). "
                    + "The response has `Cache-Control: no-store` (S0-AC-101).")
    @ApiResponse(responseCode = "204", description = "Accepted and signed in. The session cookie is set.")
    @ApiResponse(responseCode = "400", description = "`INVITATION_INVALID`: the token is unknown, expired, used, or "
            + "replaced by a newer one, or the token is sent to the other app's host (a customer's token on the console "
            + "host, a staff token on the portal host); one body for all (S0-AC-37). `INVALID_INPUT`: the password breaks AUTH-5 "
            + "(field `password`: `TOO_SHORT`, `TOO_LONG`, `BLANK`, `SAME_AS_EMAIL`).")
    @ApiResponse(responseCode = "403", description = "`CSRF_TOKEN_INVALID`.")
    @PostMapping("/accept")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void acceptInvitation(@RequestBody AcceptInvitationRequest request) {
        throw new NotImplementedException();
    }
}
