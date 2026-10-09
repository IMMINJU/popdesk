package io.popdesk.helpdesk.web.publicapi;

import io.popdesk.helpdesk.account.SignInFlow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Session")
@RestController
@RequestMapping("/api/public")
class PublicSessionController {

    private final SignInFlow flow;

    PublicSessionController(SignInFlow flow) {
        this.flow = flow;
    }

    @Operation(operationId = "getCsrfToken", summary = "Set the CSRF cookie",
            description = "Sets the readable CSRF cookie before the first request that changes data (sign-in, "
                    + "accepting an invitation). Clients send its value back in a request header.")
    @ApiResponse(responseCode = "204", description = "The CSRF cookie is set.")
    @GetMapping("/csrf")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void csrf(HttpServletRequest request) {
        // Reading the token makes the CSRF filter set the cookie when the browser has none.
        if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) {
            token.getToken();
        }
    }

    @Operation(operationId = "signIn", summary = "Sign in",
            description = "Signs in to the app of the host the request came to: customers on the portal host, "
                    + "agents and admins on the console host. Creates a new session (S0-AC-17, S0-AC-21).")
    @ApiResponse(responseCode = "204", description = "Signed in. The session cookie is set.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: email or password is empty.")
    @ApiResponse(responseCode = "401", description = "`SIGN_IN_FAILED`: the same body whether the email is unknown, "
            + "the password is wrong, the account is invited, suspended, or locked, or it belongs to the other app "
            + "(S0-AC-22, S0-AC-23).")
    @ApiResponse(responseCode = "403", description = "`CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "429", description = "`RATE_LIMITED`: more than 20 attempts from one IP address in 60 "
            + "seconds. `Retry-After` says when to try again (S0-AC-24).")
    @PostMapping("/sign-in")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void signIn(@RequestBody SignInRequest request, HttpServletRequest http, HttpServletResponse response) {
        flow.signIn(request.email(), request.password(), http, response);
    }

    @Operation(operationId = "signOut", summary = "Sign out",
            description = "Deletes the caller's session (S0-AC-27). Succeeds also without a session.")
    @ApiResponse(responseCode = "204", description = "Signed out.")
    @ApiResponse(responseCode = "403", description = "`CSRF_TOKEN_INVALID`.")
    @PostMapping("/sign-out")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void signOut(HttpServletRequest request) {
        flow.signOut(request);
    }
}
