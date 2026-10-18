package io.popdesk.helpdesk.web.console;

import io.popdesk.Paged;
import io.popdesk.helpdesk.account.CurrentAccount;
import io.popdesk.helpdesk.company.MembershipRequestService;
import io.popdesk.helpdesk.company.MembershipRequestService.Pending;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Membership requests")
@RestController
@RequestMapping("/api/console/membership-requests")
class ConsoleMembershipRequestController {

    private final MembershipRequestService requests;

    ConsoleMembershipRequestController(MembershipRequestService requests) {
        this.requests = requests;
    }

    private static PendingMembership pending(Pending p) {
        return new PendingMembership(p.id(), new CompanyRef(p.companyCode(), p.companyName()),
                new MembershipRequester(p.requesterId(), p.requesterName(), p.requesterEmail()), p.name(), p.email(),
                new MembershipRequestAccount(p.accountId(), p.accountName(), p.accountRole(), p.accountStatus(),
                        p.invitationCancelled()),
                p.requestedAt().atOffset(ZoneOffset.UTC));
    }

    static final String ADMIN_ONLY = "`FORBIDDEN`: admins only.";
    static final String NO_REQUEST = "`NOT_FOUND`: no such request.";
    static final String CLOSED = "`REQUEST_CLOSED`: the request was closed the other way, or its manager took it "
            + "back (S2-AC-37).";

    @Operation(operationId = "listMembershipRequests", summary = "Membership requests waiting for an admin",
            description = "Open requests only, oldest first (S2-AC-33).")
    @ApiResponse(responseCode = "200", description = "One page.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `page` or `size` out of range "
            + "(`OUT_OF_RANGE`), or `companyCode` names no company (`UNKNOWN_VALUE`).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY)
    @GetMapping
    Paged<PendingMembership> listMembershipRequests(
            @Parameter(description = "Only requests for this company.") @RequestParam(required = false) @Nullable
                    String companyCode,
            @Parameter(description = "Page number, from 1.") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "1 to 100.") @RequestParam(defaultValue = "20") int size) {
        return requests.list(CurrentAccount.get().viewer(), companyCode, page, size)
                .map(ConsoleMembershipRequestController::pending);
    }

    @Operation(operationId = "approveMembershipRequest", summary = "Approve a membership request",
            description = "Makes the account a member of the company and closes the request (S2-AC-34). Approving "
                    + "an approved request again is 200 too (S2-AC-37).")
    @ApiResponse(responseCode = "200", description = "Approved.")
    @ApiResponse(responseCode = "400", description = "`ACCOUNT_ANONYMIZED`: the account of the request is "
            + "anonymized; checked before the other 400s (S10A-AC-13). `NOT_A_CUSTOMER`: the account is an agent "
            + "or an admin. `COMPANY_NOT_ACTIVE`: the company is `ARCHIVED` (S2-AC-36).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY + " `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = NO_REQUEST)
    @ApiResponse(responseCode = "409", description = CLOSED)
    @PostMapping("/{id}/approve")
    void approveMembershipRequest(@PathVariable long id) {
        CurrentAccount admin = CurrentAccount.get();
        requests.approve(admin.viewer(), admin.actor(), id);
    }

    @Operation(operationId = "rejectMembershipRequest", summary = "Reject a membership request",
            description = "Closes the request; the account does not change (S2-AC-35). Works whatever the role of "
                    + "the account or the status of the company. Rejecting a rejected request again is 200 too "
                    + "(S2-AC-37).")
    @ApiResponse(responseCode = "200", description = "Rejected.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY + " `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = NO_REQUEST)
    @ApiResponse(responseCode = "409", description = CLOSED)
    @PostMapping("/{id}/reject")
    void rejectMembershipRequest(@PathVariable long id) {
        requests.reject(CurrentAccount.get().actor(), id);
    }
}
