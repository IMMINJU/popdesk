package io.popdesk.helpdesk.account;

import io.popdesk.PageRequest;
import io.popdesk.Paged;
import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.account.AccountRepository.AccountRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The admin's account list (C-10). */
@Service
public class AccountService {

    private final AccountRepository accounts;

    AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    /** Every account, or those with {@code role}, newest first. */
    @Transactional(readOnly = true)
    public Paged<AccountRow> list(Role role, int page, int size) {
        PageRequest request = PageRequest.of(page, size);
        return request.of(accounts.list(role, request.offset(), request.size()), accounts.count(role));
    }
}
