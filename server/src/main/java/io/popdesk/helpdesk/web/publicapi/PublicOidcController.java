package io.popdesk.helpdesk.web.publicapi;

import io.popdesk.helpdesk.account.SignInFlow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Session")
@RestController
@RequestMapping("/api/public")
class PublicOidcController {

    static final String SIGN_IN_PAGE = "Otherwise to the sign-in page of the host's app: `/login?oidc=failed` "
            + "(the sign-in cannot go on, one value whatever the reason), `/login?oidc=unavailable` (the provider "
            + "could not be reached or answered wrongly), `/login?oidc=busy` (too many sign-ins started)";
    static final String OFF = "`NOT_FOUND`: OIDC sign-in is not on for this host's app (S11-AC-06).";

    private final SignInFlow flow;

    PublicOidcController(SignInFlow flow) {
        this.flow = flow;
    }

    @Operation(operationId = "getSignInOptions", summary = "Ways to sign in",
            description = "Whether the sign-in page offers OIDC sign-in on this host (S11-AC-03). Answers without a "
                    + "session. `no-store`.")
    @ApiResponse(responseCode = "200", description = "The options.")
    @GetMapping("/sign-in-options")
    SignInOptions getSignInOptions(HttpServletRequest request) {
        return new SignInOptions(flow.oidcOffered(request));
    }

    @Operation(operationId = "startOidcSignIn", summary = "Start an OIDC sign-in",
            description = "A page the browser opens, not a call that reads JSON: the sign-in button moves the page "
                    + "here (S11-AC-31). Makes a sign-in request used once within 10 minutes and sets the cookie "
                    + "`__Host-POPDESK_OIDC` (https) or `POPDESK_OIDC` (http) that ties it to this browser "
                    + "(S11-AC-04). `no-store`. Takes no CSRF token.")
    @ApiResponse(responseCode = "302", description = "To the provider's authorization endpoint with "
            + "`response_type=code`, `client_id`, `redirect_uri`, `scope=openid email profile`, `state`, `nonce`, "
            + "`code_challenge`, and `code_challenge_method=S256` (S11-AC-04). " + SIGN_IN_PAGE
            + " (S11-AC-07, S11-AC-08).",
            headers = @Header(name = HttpHeaders.LOCATION, description = "Where the browser goes next.",
                    schema = @Schema(type = "string")),
            content = @Content)
    @ApiResponse(responseCode = "404", description = OFF)
    @GetMapping("/oidc/start")
    void startOidcSignIn(
            @Parameter(description = "The page to go to after signing in: a path in the same app that starts with "
                    + "one `/`, without control characters, at most 512 characters. Any other value is taken as `/` "
                    + "(S11-AC-05).") @RequestParam(required = false) @Nullable String next,
            HttpServletRequest request, HttpServletResponse response) {
        flow.oidcStart(next, request, response);
    }

    @Operation(operationId = "finishOidcSignIn", summary = "Come back from the OIDC provider",
            description = "The `redirect_uri` the provider sends the browser back to; a page, not a call that reads "
                    + "JSON. Checks the cookie and `state` first, then exchanges the code and checks the ID token, "
                    + "matches the account, and starts a new session (S11-AC-09 to S11-AC-17). The sign-in request "
                    + "is deleted whatever happens. Takes no CSRF token: the cookie and `state` stop a forged "
                    + "sign-in.")
    @ApiResponse(responseCode = "302", description = "Signed in: to the page the sign-in started with, or `/`; the "
            + "session cookie is set (S11-AC-09). The provider sent `error` (the person cancelled): to `/login` "
            + "without a query (S11-AC-12). " + SIGN_IN_PAGE + " (S11-AC-11, S11-AC-13, "
            + "S11-AC-17).",
            headers = @Header(name = HttpHeaders.LOCATION, description = "Where the browser goes next.",
                    schema = @Schema(type = "string")),
            content = @Content)
    @ApiResponse(responseCode = "404", description = OFF)
    @GetMapping("/oidc/callback")
    void finishOidcSignIn(
            @Parameter(description = "The authorization code.") @RequestParam(required = false) @Nullable String code,
            @Parameter(description = "The `state` sent at the start.") @RequestParam(required = false) @Nullable
                    String state,
            @Parameter(description = "The provider's error code, for example `access_denied`.")
                    @RequestParam(required = false) @Nullable String error,
            HttpServletRequest request, HttpServletResponse response) {
        flow.oidcCallback(code, state, error, request, response);
    }
}
