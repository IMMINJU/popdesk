package io.popdesk.helpdesk.web.portal;

import io.popdesk.Paged;
import io.popdesk.helpdesk.account.CurrentAccount;
import io.popdesk.helpdesk.company.MemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** What a customer manager does for the company where they are manager (MGR-2 to MGR-4). */
@Tag(name = "Company members")
@RestController
@RequestMapping("/api/portal/companies/{code}/members")
class PortalMemberController {

    private final MemberService members;

    PortalMemberController(MemberService members) {
        this.members = members;
    }

    static final String NOT_MANAGER = "`FORBIDDEN`: the caller belongs to the company but is not its manager "
            + "(S2-AC-09).";
    static final String NO_COMPANY = "`NOT_FOUND`: no such company, or the caller does not belong to it.";
    static final String NO_ROW = NO_COMPANY + " No row of this company has this ID (S2-AC-32).";
    static final String ROW_ID = "The `id` of the row in the member list.";
    static final String RATE = "`RATE_LIMITED`: more than 20 invitations, sent again included, in 60 seconds "
            + "(S2-AC-20). `Retry-After` says when to try again.";

    @Operation(operationId = "listCompanyMembers", summary = "Members of a company the caller manages",
            description = "Customers who belong to the company and invitations still waiting, in email order "
                    + "(S2-AC-08). Works for a company of any status (S2-AC-12).")
    @ApiResponse(responseCode = "200", description = "One page.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `page` or `size` out of range (`OUT_OF_RANGE`).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = NOT_MANAGER)
    @ApiResponse(responseCode = "404", description = NO_COMPANY)
    @GetMapping
    Paged<Member> listCompanyMembers(
            @PathVariable String code,
            @Parameter(description = "Page number, from 1.") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "1 to 100.") @RequestParam(defaultValue = "20") int size) {
        return members.list(CurrentAccount.get().viewer(), code, page, size)
                .map(r -> new Member(r.id(), r.name(), r.email(), r.status(), r.manager()));
    }

    @Operation(operationId = "inviteCompanyMember", summary = "Invite a customer to the company",
            description = "The person appears in the member list as `INVITED`. The answer is the same whether or "
                    + "not the email already has an account (S2-AC-15); it never carries a link (S2-AC-23).")
    @ApiResponse(responseCode = "204", description = "Taken.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `email` malformed or over 100, `name` empty "
            + "or over 50 (S2-AC-19). `COMPANY_NOT_ACTIVE`: the company is `ARCHIVED` (S2-AC-18).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = NOT_MANAGER + " `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = NO_COMPANY)
    @ApiResponse(responseCode = "409", description = "`ALREADY_LISTED`: the email is a row of this member list "
            + "(S2-AC-17). `TOO_MANY_PENDING`: the company has 100 `INVITED` rows (S2-AC-21).")
    @ApiResponse(responseCode = "429", description = RATE)
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void inviteCompanyMember(@PathVariable String code, @RequestBody InviteMemberRequest request) {
        CurrentAccount manager = CurrentAccount.get();
        members.invite(manager.viewer(), manager.actor(), code, request.email(), request.name());
    }

    @Operation(operationId = "removeCompanyMember", summary = "Take a member out of the company",
            description = "Only the membership of this company goes; the account and its other companies stay as "
                    + "they are (S2-AC-24). For an `INVITED` row this cancels the invitation. The answer is the "
                    + "same for every kind of row (S2-AC-27).")
    @ApiResponse(responseCode = "204", description = "Removed.")
    @ApiResponse(responseCode = "400", description = "`OWN_ACCOUNT`: the row is the caller. `MEMBER_IS_MANAGER`: "
            + "the row is another manager (S2-AC-28).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = NOT_MANAGER + " `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = NO_ROW)
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeCompanyMember(@PathVariable String code, @Parameter(description = ROW_ID) @PathVariable String id) {
        CurrentAccount manager = CurrentAccount.get();
        members.remove(manager.viewer(), manager.actor(), code, id);
    }

    @Operation(operationId = "resendMemberInvitation", summary = "Send the invitation of a member again",
            description = "For an `INVITED` row: queues the invitation email with a new link, so the previous link "
                    + "stops working (S2-AC-29).")
    @ApiResponse(responseCode = "204", description = "Taken.")
    @ApiResponse(responseCode = "400", description = "`INVITATION_NOT_RESENDABLE`: the invitation cannot be sent "
            + "again from here. One code whatever the reason (S2-AC-31).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = NOT_MANAGER + " `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = NO_ROW)
    @ApiResponse(responseCode = "429", description = RATE)
    @PostMapping("/{id}/resend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resendMemberInvitation(@PathVariable String code,
            @Parameter(description = ROW_ID) @PathVariable String id) {
        CurrentAccount manager = CurrentAccount.get();
        members.resend(manager.viewer(), manager.actor(), code, id);
    }
}
