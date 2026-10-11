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

@Tag(name = "Session")
@RestController
@RequestMapping("/api/public/password-reset")
class PublicPasswordResetController {

    @Operation(operationId = "requestPasswordReset", summary = "Ask for a password reset email",
            description = "Queues an email with a reset link for an `ACTIVE` account of the app of the host the "
                    + "request came to (S1A-AC-06). The link works for 1 hour and replaces an earlier one; a second "
                    + "request within 15 minutes sends nothing (S1A-AC-12). The answer is the same whether or not "
                    + "an email is sent (S1A-AC-05).")
    @ApiResponse(responseCode = "202", description = "Taken. The same empty answer whether the email is unknown, "
            + "the account is invited or suspended, or it belongs to the other app.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `email` is empty (`REQUIRED`).")
    @ApiResponse(responseCode = "403", description = "`CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "429", description = "`RATE_LIMITED`: more than 20 requests from one IP address in "
            + "60 seconds. `Retry-After` says when to try again (S1A-AC-13).")
    @PostMapping("/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void requestPasswordReset(@RequestBody RequestPasswordResetRequest request) {
        throw new NotImplementedException();
    }

    @Operation(operationId = "resetPassword", summary = "Set a new password with a reset link",
            description = "Sets the password, lifts a sign-in lock, and ends every session of the account. It does "
                    + "not sign in (S1A-AC-09, S1A-AC-04). The response has `Cache-Control: no-store` (S1A-AC-14).")
    @ApiResponse(responseCode = "204", description = "The password is changed.")
    @ApiResponse(responseCode = "400", description = "`PASSWORD_RESET_INVALID`: the token is unknown, over 1 hour "
            + "old, used, or replaced by a newer one, the account is no longer `ACTIVE`, or the token is sent to "
            + "the other app's host; one body for all (S1A-AC-10, S1A-AC-84). `INVALID_INPUT`: the password breaks "
            + "AUTH-5 (field `password`: `TOO_SHORT`, `TOO_LONG`, `BLANK`, `SAME_AS_EMAIL`); the token stays "
            + "usable (S1A-AC-11).")
    @ApiResponse(responseCode = "403", description = "`CSRF_TOKEN_INVALID`.")
    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resetPassword(@RequestBody ResetPasswordRequest request) {
        throw new NotImplementedException();
    }
}
