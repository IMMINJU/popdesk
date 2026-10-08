package io.popdesk.helpdesk.web.console;

import io.popdesk.NotImplementedException;
import io.popdesk.Paged;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Invitations")
@RestController
@RequestMapping("/api/console/invitations")
class ConsoleInvitationController {

    @Operation(operationId = "invite", summary = "Invite a person",
            description = "Creates an `INVITED` account and queues the invitation email (S0-AC-33). A customer joins "
                    + "the given companies. Without SMTP the response carries the link instead, once. The response "
                    + "has `Cache-Control: no-store` (S0-AC-101).")
    @ApiResponse(responseCode = "201", description = "Invited.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `email` malformed or over 100, `name` empty "
            + "or over 50, `role` unknown, `companyCodes` empty for a customer (`REQUIRED`), given for staff "
            + "(`NOT_ALLOWED`), or naming a company that does not exist or is not `ACTIVE` (`UNKNOWN_VALUE`) "
            + "(S0-AC-34).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`FORBIDDEN`: admins only. `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "409", description = "`EMAIL_TAKEN`: an account has this email (S0-AC-34).")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    InvitationResult invite(@RequestBody CreateInvitationRequest request) {
        throw new NotImplementedException();
    }

    @Operation(operationId = "listPendingInvitations", summary = "Accounts that have not accepted yet",
            description = "`INVITED` accounts, newest invitation first (S0-AC-40).")
    @ApiResponse(responseCode = "200", description = "One page.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `page` or `size` out of range (`OUT_OF_RANGE`).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`FORBIDDEN`: admins only.")
    @GetMapping
    Paged<PendingInvitation> listPendingInvitations(
            @Parameter(description = "Page number, from 1.") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "1 to 100.") @RequestParam(defaultValue = "20") int size) {
        throw new NotImplementedException();
    }

    @Operation(operationId = "createInvitationLink", summary = "Make a new invitation link to copy",
            description = "Only without SMTP. Makes a new token, so the previous link stops working, and returns the "
                    + "link once (S0-AC-38). The response has `Cache-Control: no-store` (S0-AC-101).")
    @ApiResponse(responseCode = "200", description = "The new link.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`FORBIDDEN`: admins only. `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: SMTP is configured (S0-AC-39), or no `INVITED` "
            + "account has this ID.")
    @PostMapping("/{accountId}/link")
    InvitationLink createInvitationLink(@PathVariable long accountId) {
        throw new NotImplementedException();
    }
}
