package io.popdesk.helpdesk.web.portal;

import io.popdesk.PatchText;
import io.popdesk.helpdesk.account.AccountRepository.AccountRow;
import io.popdesk.helpdesk.account.CurrentAccount;
import io.popdesk.helpdesk.account.OwnAccountService;
import io.popdesk.helpdesk.account.OwnAccountService.Own;
import io.popdesk.helpdesk.account.SignInFlow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Session")
@RestController
class PortalMeController {

    private final OwnAccountService own;
    private final SignInFlow flow;

    PortalMeController(OwnAccountService own, SignInFlow flow) {
        this.own = own;
        this.flow = flow;
    }

    @Operation(operationId = "getMe", summary = "The signed-in customer",
            description = "Also tells whether the session is still valid.")
    @ApiResponse(responseCode = "200", description = "Signed in.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`: no session, or it ended (S0-AC-25).")
    @GetMapping("/api/portal/me")
    Me getMe() {
        return me(own.get(CurrentAccount.get()));
    }

    @Operation(operationId = "updateMe", summary = "Change the caller's name or language",
            description = "A field left out keeps its value. Later email to the account uses the new language "
                    + "(S1A-AC-16).")
    @ApiResponse(responseCode = "200", description = "Saved.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `name` empty or over 50, or `null` "
            + "(`REQUIRED`); `locale` not `ko` or `en` (`UNKNOWN_VALUE`), or `null` (`REQUIRED`).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`CSRF_TOKEN_INVALID`.")
    @PatchMapping("/api/portal/me")
    Me updateMe(@RequestBody UpdateMeRequest request) {
        return me(own.update(CurrentAccount.get(), PatchText.of("name", request.name()),
                PatchText.of("locale", request.locale())));
    }

    @Operation(operationId = "changeMyPassword", summary = "Change the caller's password",
            description = "Ends every other session of the account and gives this one a new session ID "
                    + "(S1A-AC-02, S1A-AC-17).")
    @ApiResponse(responseCode = "204", description = "The password is changed. The session cookie is set again.")
    @ApiResponse(responseCode = "400", description = "`CURRENT_PASSWORD_WRONG` (S1A-AC-18). `INVALID_INPUT`: the "
            + "new password breaks AUTH-5 (field `newPassword`: `TOO_SHORT`, `TOO_LONG`, `BLANK`, `SAME_AS_EMAIL`).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "429", description = "`RATE_LIMITED`: the current password was wrong five times in "
            + "a row, so this request is refused for 15 minutes. `Retry-After` says when to try again (S1A-AC-18).")
    @PutMapping("/api/portal/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changeMyPassword(@RequestBody ChangePasswordRequest request, HttpServletRequest http,
            HttpServletResponse response) {
        flow.changePassword(request.currentPassword(), request.newPassword(), http, response);
    }

    static Me me(Own own) {
        AccountRow account = own.account();
        return new Me(account.id(), account.email(), account.name(), account.role(), account.locale(),
                account.photoUpdatedAt() == null ? null : account.photoUpdatedAt().atOffset(ZoneOffset.UTC),
                own.companies().stream().map(c -> new Company(c.code(), c.name(), c.status())).toList());
    }
}
