package io.popdesk.helpdesk.account;

import io.popdesk.ApiError;
import io.popdesk.ApiException;
import io.popdesk.PageRequest;
import io.popdesk.Paged;
import io.popdesk.TextInput;
import io.popdesk.Transactions;
import io.popdesk.helpdesk.AccountStatus;
import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.account.AccountRepository.AccountRow;
import io.popdesk.helpdesk.audit.AuditLog;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What an admin reads and changes about accounts (ACCT-3, S1A-AC-29 to S1A-AC-37): the account list
 * (C-10), names, roles, suspension, and password resets. The security filter has checked that the
 * caller is an admin; here the account is looked up first (404), then the input (400), then the state
 * (400). Each change writes its audit row in its own transaction (S1A-AC-62).
 *
 * <p>Whether an account accepted its invitation is read from its password hash: null until it accepts
 * (S1A-AC-90).
 */
@Service
public class AccountService {

    /** The longest search text (COM-5). */
    static final int SEARCH_MAX = 100;

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accounts;
    private final Sessions sessions;
    private final PasswordResetService resets;
    private final AuditLog audit;
    private final Transactions transactions;
    private final Clock clock;

    AccountService(AccountRepository accounts, Sessions sessions, PasswordResetService resets, AuditLog audit,
            Transactions transactions, Clock clock) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.resets = resets;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Every account, or those with {@code role} and {@code status} whose name or email holds {@code q},
     * newest first (S1A-AC-29). {@code q} is 1 to 100 characters and is searched as it is: {@code %} and
     * {@code _} find themselves (S1A-AC-89).
     */
    @Transactional(readOnly = true)
    public Paged<AccountRow> list(Role role, AccountStatus status, String q, int page, int size) {
        String text = q == null ? null : Normalizer.normalize(q, Normalizer.Form.NFC);
        if (text != null && text.isEmpty()) {
            throw ApiException.invalid("q", "TOO_SHORT");
        }
        if (text != null && TextInput.length(text) > SEARCH_MAX) {
            throw ApiException.invalid("q", "TOO_LONG");
        }
        PageRequest request = PageRequest.of(page, size);
        return request.of(accounts.list(role, status, text, request.offset(), request.size()),
                accounts.count(role, status, text));
    }

    /**
     * Changes an account's name, also before it accepted its invitation (S1A-AC-30). A null name, or the
     * name the account has, changes nothing and is not audited.
     */
    public AccountRow rename(CurrentAccount admin, long accountId, String name) {
        return transactions.run(() -> {
            AccountRow account = accounts.lockById(accountId).orElseThrow(ApiException::notFound);
            List<ApiError.FieldError> errors = new ArrayList<>();
            String cleanName = name == null ? null
                    : TextInput.required("name", name, InvitationService.NAME_MAX, errors);
            if (!errors.isEmpty()) {
                throw ApiException.invalid(errors);
            }
            if (cleanName == null || cleanName.equals(account.name())) {
                return account;
            }
            accounts.updateName(accountId, cleanName, now());
            audit.record(admin.actor(), "ACCOUNT_NAME_CHANGED", target(accountId),
                    Map.of("name", change(account.name(), cleanName)));
            return accounts.findById(accountId).orElseThrow();
        });
    }

    /**
     * Makes an agent an admin or an admin an agent, and ends every session of the account (S1A-AC-03,
     * S1A-AC-31). Company assignments stay. The role the account has changes nothing.
     */
    public AccountRow changeRole(CurrentAccount admin, long accountId, Role role) {
        AccountRow changed = changeRoleOrSuspend(admin, accountId, false, role);
        log.info("The role of account {} is {}.", accountId, changed.role());
        return changed;
    }

    /**
     * Makes an {@code ACTIVE} account {@code SUSPENDED} and ends every session of it (S1A-AC-01,
     * S1A-AC-34). A suspended account stays as it is. An account that never accepted its invitation is
     * not suspended; its invitation is cancelled instead.
     */
    public AccountRow suspend(CurrentAccount admin, long accountId) {
        AccountRow changed = changeRoleOrSuspend(admin, accountId, true, null);
        log.info("Account {} is suspended.", accountId);
        return changed;
    }

    /**
     * The one way an account stops being an {@code ACTIVE} admin: a suspension or a change to
     * {@code role}. It locks the account and every {@code ACTIVE} admin in ID order before it reads them,
     * so of two admins who take each other down at the same time the second finds no other admin left
     * and is refused (S1A-AC-81). The caller's own account is refused before the admins are counted
     * (S1A-AC-33).
     */
    private AccountRow changeRoleOrSuspend(CurrentAccount admin, long accountId, boolean suspension, Role role) {
        return transactions.run(() -> {
            List<AccountRow> locked = accounts.lockWithActiveAdmins(accountId);
            AccountRow account = locked.stream().filter(a -> a.id() == accountId).findFirst()
                    .orElseThrow(ApiException::notFound);
            if (!suspension && role == null) {
                throw ApiException.invalid("role", "REQUIRED");
            }
            if (account.id() == admin.id()) {
                throw refused("OWN_ACCOUNT", "This cannot be done to your own account.");
            }
            if (suspension) {
                if (account.passwordHash() == null) {
                    throw accountNotActive();
                }
                if (account.status() == AccountStatus.SUSPENDED) {
                    return account;
                }
            } else {
                if (role == Role.CUSTOMER || account.role() == Role.CUSTOMER) {
                    throw refused("CUSTOMER_ROLE_FIXED", "Only agents and admins change into each other.");
                }
                if (account.role() == role) {
                    return account;
                }
            }
            if (activeAdmin(account) && locked.stream().noneMatch(a -> a.id() != accountId && activeAdmin(a))) {
                throw refused("LAST_ADMIN", "This would leave no active admin.");
            }
            Instant now = now();
            if (suspension) {
                accounts.updateStatus(accountId, AccountStatus.SUSPENDED, now);
                audit.record(admin.actor(), "ACCOUNT_SUSPENDED", target(accountId),
                        Map.of("status", change(account.status().name(), AccountStatus.SUSPENDED.name())));
            } else {
                accounts.updateRole(accountId, role, now);
                audit.record(admin.actor(), "ACCOUNT_ROLE_CHANGED", target(accountId),
                        Map.of("role", change(account.role().name(), role.name())));
            }
            sessions.endAll(accountId);
            return accounts.findById(accountId).orElseThrow();
        });
    }

    /**
     * Makes a {@code SUSPENDED} account {@code ACTIVE} again (S1A-AC-35). An active account stays as it
     * is. An account that never accepted its invitation is not brought back this way; its invitation is
     * sent again instead.
     */
    public AccountRow reactivate(CurrentAccount admin, long accountId) {
        AccountRow changed = transactions.run(() -> {
            AccountRow account = accounts.lockById(accountId).orElseThrow(ApiException::notFound);
            if (account.passwordHash() == null) {
                throw refused("INVITATION_NOT_ACCEPTED", "This account never accepted its invitation. Send the "
                        + "invitation again instead.");
            }
            if (account.status() == AccountStatus.ACTIVE) {
                return account;
            }
            accounts.updateStatus(accountId, AccountStatus.ACTIVE, now());
            audit.record(admin.actor(), "ACCOUNT_REACTIVATED", target(accountId),
                    Map.of("status", change(account.status().name(), AccountStatus.ACTIVE.name())));
            return accounts.findById(accountId).orElseThrow();
        });
        log.info("Account {} is active.", accountId);
        return changed;
    }

    /**
     * Resets the password of an {@code ACTIVE} account (S1A-AC-36): the password it has stops working at
     * once, a sign-in lock is lifted, every session ends (S1A-AC-04), and a new reset link is made
     * whatever the time since the last one (S1A-AC-37). The stored hash becomes a marker that no password
     * matches, never null, so the account still counts as one that accepted its invitation.
     *
     * @return the link when there is no SMTP to mail it; empty when its email is queued
     */
    public Optional<String> resetPassword(CurrentAccount admin, long accountId) {
        Optional<String> link = transactions.run(() -> {
            AccountRow account = accounts.lockById(accountId).orElseThrow(ApiException::notFound);
            if (account.status() != AccountStatus.ACTIVE) {
                throw accountNotActive();
            }
            accounts.updatePassword(accountId, PasswordHashes.RESET, now());
            sessions.endAll(accountId);
            Optional<String> made = resets.issue(account);
            audit.record(admin.actor(), "ACCOUNT_PASSWORD_RESET", target(accountId), null);
            return made;
        });
        log.info("The password of account {} was reset by an admin.", accountId);
        return link;
    }

    private static boolean activeAdmin(AccountRow account) {
        return account.role() == Role.ADMIN && account.status() == AccountStatus.ACTIVE;
    }

    /** The target of an audit row about an account: its ID, never its email. */
    static Map<String, Object> target(long accountId) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("type", "ACCOUNT");
        target.put("accountId", accountId);
        return target;
    }

    /** A changed value of an audit row: what it was and what it is (S1A-AC-63). */
    static Map<String, Object> change(String from, String to) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("from", from);
        change.put("to", to);
        return change;
    }

    private static ApiException accountNotActive() {
        return refused("ACCOUNT_NOT_ACTIVE", "This account is not active.");
    }

    private static ApiException refused(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
