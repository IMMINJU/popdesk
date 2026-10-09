package io.popdesk.helpdesk.web.console;

import io.popdesk.Paged;
import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.account.AccountRepository.AccountRow;
import io.popdesk.helpdesk.account.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Accounts")
@RestController
class ConsoleAccountController {

    private final AccountService accounts;

    ConsoleAccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @Operation(operationId = "listAccounts", summary = "Accounts",
            description = "Every account, newest first (C-10). `role` narrows it, for example to pick an agent to "
                    + "assign (C-9).")
    @ApiResponse(responseCode = "200", description = "One page.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `page` or `size` out of range "
            + "(`OUT_OF_RANGE`), unknown `role` (`UNKNOWN_VALUE`).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`FORBIDDEN`: admins only.")
    @GetMapping("/api/console/accounts")
    Paged<Account> listAccounts(
            @Parameter(description = "Only accounts with this role.") @RequestParam(required = false) @Nullable Role role,
            @Parameter(description = "Page number, from 1.") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "1 to 100.") @RequestParam(defaultValue = "20") int size) {
        Paged<AccountRow> rows = accounts.list(role, page, size);
        return new Paged<>(rows.items().stream().map(a -> new Account(a.id(), a.email(), a.name(), a.role(), a.status(),
                a.createdAt().atOffset(ZoneOffset.UTC))).toList(), rows.page(), rows.size(), rows.totalElements(),
                rows.totalPages());
    }
}
