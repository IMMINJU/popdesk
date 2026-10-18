package io.popdesk.helpdesk.web.console;

import io.popdesk.NotImplementedException;
import io.popdesk.Paged;
import io.popdesk.PatchText;
import io.popdesk.helpdesk.AccountStatus;
import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.account.AccountRepository.AccountRow;
import io.popdesk.helpdesk.account.AccountService;
import io.popdesk.helpdesk.account.CurrentAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Tag(name = "Accounts")
@RestController
class ConsoleAccountController {

    private final AccountService accounts;

    ConsoleAccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    static Account account(AccountRow a) {
        return new Account(a.id(), a.email(), a.name(), a.role(), a.status(), a.createdAt().atOffset(ZoneOffset.UTC),
                a.photoUpdatedAt() == null ? null : a.photoUpdatedAt().atOffset(ZoneOffset.UTC), a.anonymized());
    }

    static final String ADMIN_ONLY = "`FORBIDDEN`: admins only. `CSRF_TOKEN_INVALID`.";
    static final String NOT_FOUND = "`NOT_FOUND`: no such account.";
    static final String ANONYMIZED = "`ACCOUNT_ANONYMIZED`: the account is anonymized; checked before the other "
            + "400s (S10A-AC-13).";
    static final String CLEAR_ASSIGNMENTS = "`true`: in the same request, also clear the assignee of every issue counted by "
            + "`countOpenAssignments` (S3-AC-69). Each of them gets a history entry by the caller, and no webhook message "
            + "is sent (S3-AC-70). Left out or `false`, assignees stay.";

    @Operation(operationId = "listAccounts", summary = "Accounts",
            description = "Every account, newest first (C-10). `role` narrows it, for example to pick an agent to "
                    + "assign (C-9); `status`, `q`, and `anonymized` narrow it further (S1A-AC-29, S10A-AC-36).")
    @ApiResponse(responseCode = "200", description = "One page.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `page` or `size` out of range "
            + "(`OUT_OF_RANGE`), unknown `role` or `status` (`UNKNOWN_VALUE`), `q` empty or over 100 characters "
            + "(`TOO_SHORT`, `TOO_LONG`).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`FORBIDDEN`: admins only.")
    @ApiResponse(responseCode = "429", description = "`RATE_LIMITED`: more than 60 lists with `q` in 60 seconds, "
            + "counted together with the other lists that search (S3-AC-15, COM-8).")
    @GetMapping("/api/console/accounts")
    Paged<Account> listAccounts(
            @Parameter(description = "Only accounts with this role.") @RequestParam(required = false) @Nullable Role role,
            @Parameter(description = "Only accounts with this status.") @RequestParam(required = false) @Nullable
                    AccountStatus status,
            @Parameter(description = "Only accounts whose name or email holds this text, 1 to 100 characters. Case "
                    + "is ignored; `%` and `_` are ordinary characters (COM-5).")
                    @RequestParam(required = false) @Nullable String q,
            @Parameter(description = "`false`: only accounts that are not anonymized; `true`: only anonymized "
                    + "ones. Left out, both. The customer search of C-9 sends `false` (S10A-AC-36).")
                    @RequestParam(required = false) @Nullable Boolean anonymized,
            @Parameter(description = "Page number, from 1.") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "1 to 100.") @RequestParam(defaultValue = "20") int size) {
        Paged<AccountRow> rows = accounts.list(CurrentAccount.get(), role, status, anonymized, q, page,
                size);
        return new Paged<>(rows.items().stream().map(ConsoleAccountController::account).toList(), rows.page(),
                rows.size(), rows.totalElements(), rows.totalPages());
    }

    @Operation(operationId = "updateAccount", summary = "Change an account's name",
            description = "Also for an account that has not accepted its invitation yet (S1A-AC-30).")
    @ApiResponse(responseCode = "200", description = "Saved.")
    @ApiResponse(responseCode = "400", description = ANONYMIZED + " `INVALID_INPUT`: `name` empty or over 50, or "
            + "`null` (`REQUIRED`).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY)
    @ApiResponse(responseCode = "404", description = NOT_FOUND)
    @PatchMapping("/api/console/accounts/{accountId}")
    Account updateAccount(@PathVariable long accountId, @RequestBody UpdateAccountRequest request) {
        return account(accounts.rename(CurrentAccount.get(), accountId, PatchText.of("name", request.name())));
    }

    @Operation(operationId = "changeAccountRole", summary = "Make an agent an admin, or an admin an agent",
            description = "Ends every session of the account (S1A-AC-03). An agent's company assignments stay "
                    + "(S1A-AC-31).")
    @ApiResponse(responseCode = "200", description = "Changed.")
    @ApiResponse(responseCode = "400", description = ANONYMIZED + " `INVALID_INPUT`: `role` missing (`REQUIRED`) or "
            + "unknown (`UNKNOWN_VALUE`). `CUSTOMER_ROLE_FIXED`: `role` is `CUSTOMER`, or the account is a customer "
            + "(S1A-AC-32). `OWN_ACCOUNT`: the caller's own account (S1A-AC-33). `LAST_ADMIN`: no `ACTIVE` admin "
            + "would be left (S1A-AC-81).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY)
    @ApiResponse(responseCode = "404", description = NOT_FOUND)
    @PutMapping("/api/console/accounts/{accountId}/role")
    Account changeAccountRole(@PathVariable long accountId, @RequestBody ChangeRoleRequest request) {
        return account(accounts.changeRole(CurrentAccount.get(), accountId, request.role()));
    }

    @Operation(operationId = "suspendAccount", summary = "Suspend an account",
            description = "Makes an `ACTIVE` account `SUSPENDED` and ends every session of it (S1A-AC-01, "
                    + "S1A-AC-34). A suspended account cannot sign in and gets no email. Suspending an account that "
                    + "is already `SUSPENDED` is 200 too, also when it is anonymized (S10A-AC-13). Issues keep the "
                    + "account as assignee, marked `assigneeUnavailable`, unless `clearAssignments` is sent "
                    + "(S3-AC-31).")
    @ApiResponse(responseCode = "200", description = "Suspended.")
    @ApiResponse(responseCode = "400", description = "`OWN_ACCOUNT`: the caller's own account (S1A-AC-33). "
            + "`LAST_ADMIN`: no `ACTIVE` admin would be left (S1A-AC-81). `ACCOUNT_NOT_ACTIVE`: the account never "
            + "accepted its invitation (`INVITED`, or a cancelled invitation); cancel the invitation instead.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY)
    @ApiResponse(responseCode = "404", description = NOT_FOUND)
    @PostMapping("/api/console/accounts/{accountId}/suspend")
    SuspendedAccount suspendAccount(@PathVariable long accountId,
            @Parameter(description = CLEAR_ASSIGNMENTS) @RequestParam(defaultValue = "false")
                    boolean clearAssignments) {
        AccountService.Suspension suspension = accounts.suspend(CurrentAccount.get(), accountId, clearAssignments);
        Account a = account(suspension.account());
        return new SuspendedAccount(a.id(), a.email(), a.name(), a.role(), a.status(), a.createdAt(),
                a.photoUpdatedAt(), a.anonymized(), new ClearedAssignments(suspension.clearedIssues()));
    }

    @Operation(operationId = "countOpenAssignments", summary = "What an account is still assigned to",
            description = "Issues that are not `CLOSED` and have the account as assignee, in every company: what "
                    + "`suspendAccount` clears with `clearAssignments` (S3-AC-68).")
    @ApiResponse(responseCode = "200", description = "The counts.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`FORBIDDEN`: admins only.")
    @ApiResponse(responseCode = "404", description = NOT_FOUND)
    @GetMapping("/api/console/accounts/{accountId}/open-assignments")
    OpenAssignments countOpenAssignments(@PathVariable long accountId) {
        io.popdesk.helpdesk.OpenAssignments.Counts open = accounts.openAssignments(CurrentAccount.get(), accountId);
        return new OpenAssignments(open.issues(), open.tasks());
    }

    @Operation(operationId = "reactivateAccount", summary = "Lift an account's suspension",
            description = "Makes a `SUSPENDED` account `ACTIVE` (S1A-AC-35). For an account that is already "
                    + "`ACTIVE` it is 200 too.")
    @ApiResponse(responseCode = "200", description = "Active again.")
    @ApiResponse(responseCode = "400", description = ANONYMIZED + " `INVITATION_NOT_ACCEPTED`: the account never "
            + "accepted its invitation (a cancelled invitation); send the invitation again instead (S1A-AC-35).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY)
    @ApiResponse(responseCode = "404", description = NOT_FOUND)
    @PostMapping("/api/console/accounts/{accountId}/reactivate")
    Account reactivateAccount(@PathVariable long accountId) {
        return account(accounts.reactivate(CurrentAccount.get(), accountId));
    }

    @Operation(operationId = "resetAccountPassword", summary = "Reset an account's password",
            description = "The current password of an `ACTIVE` account stops working, a sign-in lock is lifted, "
                    + "every session of the account ends, and the reset email is queued (S1A-AC-04, S1A-AC-36). "
                    + "Without SMTP the response carries the link instead, once (S1A-AC-37). The response has "
                    + "`Cache-Control: no-store`.")
    @ApiResponse(responseCode = "200", description = "Reset.")
    @ApiResponse(responseCode = "400", description = ANONYMIZED + " `ACCOUNT_NOT_ACTIVE`: the account is `INVITED` "
            + "or `SUSPENDED`.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY)
    @ApiResponse(responseCode = "404", description = NOT_FOUND)
    @PostMapping("/api/console/accounts/{accountId}/password-reset")
    PasswordResetLink resetAccountPassword(@PathVariable long accountId) {
        return new PasswordResetLink(accounts.resetPassword(CurrentAccount.get(), accountId).orElse(null));
    }

    @Operation(operationId = "exportAccount", summary = "Export an account's data",
            description = "One JSON file with the account, its profile photo, the issues it reported, and the "
                    + "comments and attachments it wrote (PII-3, S10A-AC-01 to S10A-AC-05), streamed. It holds no "
                    + "other account's ID, name, or email. An anonymized account is exported with its anonymous "
                    + "values and what it wrote (S10A-AC-07). The export is recorded as `ACCOUNT_EXPORTED` "
                    + "(S10A-AC-06). The response has `Cache-Control: no-store`.")
    @ApiResponse(responseCode = "200", description = "The file.",
            headers = @Header(name = HttpHeaders.CONTENT_DISPOSITION, description = "`attachment; "
                    + "filename=\"popdesk-account-{accountId}.json\"`.", schema = @Schema(type = "string")),
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AccountExport.class)))
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`FORBIDDEN`: admins only.")
    @ApiResponse(responseCode = "404", description = NOT_FOUND + " Also account 0, popdesk itself.")
    @ApiResponse(responseCode = "503", description = "`SERVER_BUSY`: 2 account exports are running; try again "
            + "after `Retry-After`. Counted apart from audit log exports.",
            headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait before trying again.",
                    schema = @Schema(type = "integer")))
    @GetMapping("/api/console/accounts/{accountId}/export")
    ResponseEntity<StreamingResponseBody> exportAccount(@PathVariable long accountId) {
        throw new NotImplementedException();
    }

    @Operation(operationId = "headAccountExport", summary = "Headers of an account export",
            description = "Answers as `exportAccount` would, without the body. The console calls it before it "
                    + "starts the download. It records nothing in the audit log and takes none of the 2 export "
                    + "places; it only answers 503 while both are taken.")
    @ApiResponse(responseCode = "200", description = "The export would start.",
            headers = @Header(name = HttpHeaders.CONTENT_DISPOSITION, description = "`attachment; "
                    + "filename=\"popdesk-account-{accountId}.json\"`.", schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`FORBIDDEN`: admins only.")
    @ApiResponse(responseCode = "404", description = NOT_FOUND + " Also account 0, popdesk itself. A `HEAD` "
            + "response has no body.")
    @ApiResponse(responseCode = "503", description = "`SERVER_BUSY`: 2 account exports are running; try again "
            + "after `Retry-After`. A `HEAD` response has no body.",
            headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait before trying again.",
                    schema = @Schema(type = "integer")))
    @RequestMapping(path = "/api/console/accounts/{accountId}/export", method = RequestMethod.HEAD)
    ResponseEntity<Void> headAccountExport(@PathVariable long accountId) {
        throw new NotImplementedException();
    }

    @Operation(operationId = "anonymizeAccount", summary = "Anonymize an account",
            description = "Cannot be undone (PII-4). The name becomes the anonymous name in the installation's "
                    + "language (`POPDESK_LOCALE`), the email `deleted-{id}@invalid`, and the status `SUSPENDED`; "
                    + "an `INVITED` account too (S10A-AC-08). In the same transaction it ends every session of "
                    + "the account and removes its profile photo, password, sign-in lock, invitation and password "
                    + "reset tokens, queued emails to it, and its inbox items (S10A-AC-09), puts the anonymous "
                    + "values into its membership requests (S10A-AC-10) and into its audit entries, and computes "
                    + "the audit chain again from the first changed entry (S10A-AC-17, S10A-AC-18). What it "
                    + "wrote stays, shown with the anonymous name (S10A-AC-11). Its old email can be invited "
                    + "again as a new account (S10A-AC-15). Recorded as `AUDIT_RECHAINED` and then "
                    + "`ACCOUNT_ANONYMIZED` (S10A-AC-16). Issues keep the account as assignee, marked "
                    + "`assigneeUnavailable`, unless `clearAssignments` is sent (S10A-AC-14).")
    @ApiResponse(responseCode = "200", description = "Anonymized; `anonymized` is `true`.")
    @ApiResponse(responseCode = "400", description = ANONYMIZED + " `OWN_ACCOUNT`: the caller's own account. "
            + "`LAST_ADMIN`: no `ACTIVE` admin would be left (S10A-AC-12). Nothing changed.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY)
    @ApiResponse(responseCode = "404", description = NOT_FOUND + " Also account 0, popdesk itself.")
    @ApiResponse(responseCode = "409", description = "`ANONYMIZATION_RUNNING`: another anonymization is running; "
            + "one runs at a time (S10A-AC-21). Nothing changed.")
    @PostMapping("/api/console/accounts/{accountId}/anonymize")
    SuspendedAccount anonymizeAccount(@PathVariable long accountId,
            @Parameter(description = CLEAR_ASSIGNMENTS) @RequestParam(defaultValue = "false")
                    boolean clearAssignments) {
        throw new NotImplementedException();
    }
}
