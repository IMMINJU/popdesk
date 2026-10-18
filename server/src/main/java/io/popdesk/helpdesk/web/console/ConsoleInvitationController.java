package io.popdesk.helpdesk.web.console;

import io.popdesk.Paged;
import io.popdesk.helpdesk.account.CurrentAccount;
import io.popdesk.helpdesk.account.InvitationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.ZoneOffset;
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

    private final InvitationService invitations;

    ConsoleInvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

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
        InvitationService.Invited invited = invitations.invite(CurrentAccount.get(), request.email(), request.name(),
                request.role(), request.companyCodes());
        return new InvitationResult(invited.accountId(), invited.inviteLink());
    }

    @Operation(operationId = "listPendingInvitations", summary = "Accounts that have not accepted yet",
            description = "`INVITED` accounts, newest invitation first (S0-AC-40), and cancelled invitations, marked "
                    + "`cancelled` (S1A-AC-42).")
    @ApiResponse(responseCode = "200", description = "One page.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `page` or `size` out of range (`OUT_OF_RANGE`).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`FORBIDDEN`: admins only.")
    @GetMapping
    Paged<PendingInvitation> listPendingInvitations(
            @Parameter(description = "Page number, from 1.") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "1 to 100.") @RequestParam(defaultValue = "20") int size) {
        Paged<InvitationService.Pending> rows = invitations.pending(CurrentAccount.get(), page, size);
        return new Paged<>(rows.items().stream().map(p -> new PendingInvitation(p.row().accountId(), p.row().email(),
                p.row().name(), p.row().role(),
                p.companies().stream().map(c -> new CompanyRef(c.code(), c.name())).toList(),
                p.row().invitedAt().atOffset(ZoneOffset.UTC), p.row().expiresAt().atOffset(ZoneOffset.UTC),
                p.expired(), p.row().cancelled())).toList(), rows.page(), rows.size(), rows.totalElements(), rows.totalPages());
    }

    @Operation(operationId = "resendInvitation", summary = "Send an invitation again",
            description = "Makes a new token, so the previous link stops working, and queues the invitation email; "
                    + "the link works for 7 days from now (S1A-AC-38). A cancelled invitation becomes `INVITED` "
                    + "again (S1A-AC-40). Without SMTP the response carries the link instead, once. The response "
                    + "has `Cache-Control: no-store`.")
    @ApiResponse(responseCode = "200", description = "Sent again.")
    @ApiResponse(responseCode = "400", description = "`ACCOUNT_ANONYMIZED`: the account is anonymized; checked "
            + "before the other 400s (S10A-AC-13). `INVITATION_ALREADY_ACCEPTED`: the account accepted its "
            + "invitation before (S1A-AC-41).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`FORBIDDEN`: admins only. `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: no such account.")
    @PostMapping("/{accountId}/resend")
    InvitationResult resendInvitation(@PathVariable long accountId) {
        InvitationService.Invited invited = invitations.resend(CurrentAccount.get(), accountId);
        return new InvitationResult(invited.accountId(), invited.inviteLink());
    }

    @Operation(operationId = "cancelInvitation", summary = "Cancel an invitation",
            description = "Makes the account `SUSPENDED` and its link stop working; an invitation email still "
                    + "queued is not sent (S1A-AC-39). The account stays in the list as `cancelled`.")
    @ApiResponse(responseCode = "204", description = "Cancelled.")
    @ApiResponse(responseCode = "400", description = "`INVITATION_ALREADY_ACCEPTED`: the account accepted its "
            + "invitation before (S1A-AC-41).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`FORBIDDEN`: admins only. `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: no such account.")
    @PostMapping("/{accountId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancelInvitation(@PathVariable long accountId) {
        invitations.cancel(CurrentAccount.get(), accountId);
    }

    @Operation(operationId = "createInvitationLink", summary = "Make a new invitation link to copy",
            description = "Only without SMTP. Makes a new token, so the previous link stops working, and returns the "
                    + "link once (S0-AC-38). The response has `Cache-Control: no-store` (S0-AC-101).")
    @ApiResponse(responseCode = "200", description = "The new link.")
    @ApiResponse(responseCode = "400", description = "`ACCOUNT_ANONYMIZED`: the account is anonymized; checked "
            + "before the 404 for an account that is not `INVITED` (S10A-AC-13).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`FORBIDDEN`: admins only. `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: SMTP is configured (S0-AC-39), or no `INVITED` "
            + "account has this ID.")
    @PostMapping("/{accountId}/link")
    InvitationLink createInvitationLink(@PathVariable long accountId) {
        return new InvitationLink(invitations.newLink(CurrentAccount.get(), accountId));
    }
}
