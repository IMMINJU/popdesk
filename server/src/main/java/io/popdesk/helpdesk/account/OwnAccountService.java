package io.popdesk.helpdesk.account;

import io.popdesk.ApiError;
import io.popdesk.ApiErrors;
import io.popdesk.ApiException;
import io.popdesk.PatchText;
import io.popdesk.Transactions;
import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.account.AccountRepository.AccountRow;
import io.popdesk.helpdesk.audit.AuditAction;
import io.popdesk.helpdesk.audit.AuditLog;
import io.popdesk.helpdesk.company.CompanyRepository;
import io.popdesk.helpdesk.company.CompanyRepository.CompanyRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What an account reads and changes about itself (ACCT-1, ACCT-2, S1A-AC-16 to S1A-AC-19): its details
 * with its companies, its name and language, and its password.
 *
 * <p>Wrong current passwords count with the wrong passwords of sign-in ({@code failed_sign_ins},
 * {@code locked_until}): five in a row refuse the password change and the account's sign-in for 15
 * minutes (S1A-AC-18), so an open session does not let its holder try passwords without end.
 */
@Service
public class OwnAccountService {

    /** The languages an account can have (MAIL-1). */
    static final Set<String> LOCALES = Set.of("ko", "en");

    private static final Logger log = LoggerFactory.getLogger(OwnAccountService.class);

    /**
     * An account as it sees itself: with the companies a customer belongs to or an agent is assigned to,
     * in code order; none for an admin. {@code managed}: the codes of the companies where a customer is a
     * manager (S2-AC-07).
     */
    public record Own(AccountRow account, List<CompanyRow> companies, Set<String> managed) {}

    private final AccountRepository accounts;
    private final CompanyRepository companies;
    private final Sessions sessions;
    private final PasswordEncoder passwords;
    private final AuditLog audit;
    private final Transactions transactions;
    private final Clock clock;

    OwnAccountService(AccountRepository accounts, CompanyRepository companies, Sessions sessions,
            PasswordEncoder passwords, AuditLog audit, Transactions transactions, Clock clock) {
        this.accounts = accounts;
        this.companies = companies;
        this.sessions = sessions;
        this.passwords = passwords;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** The caller's own details (S1A-AC-19). */
    @Transactional(readOnly = true)
    public Own get(CurrentAccount me) {
        return new Own(row(me), me.role() == Role.ADMIN ? List.of() : companies.listOwn(me.viewer()),
                me.role() == Role.CUSTOMER ? Set.copyOf(companies.listManagedCodes(me.viewer())) : Set.of());
    }

    /**
     * Changes the caller's name, language, or both (S1A-AC-16); a field left out keeps what the account
     * has, and one sent as null is {@code REQUIRED} (ADR D-44).
     * A 400 lists every invalid field and saves nothing.
     */
    public Own update(CurrentAccount me, PatchText name, PatchText sentLocale) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        String cleanName = name.required("name", InvitationService.NAME_MAX, errors);
        String locale = sentLocale.value();
        if (sentLocale.sent() && (locale == null || !LOCALES.contains(locale))) {
            errors.add(new ApiError.FieldError("locale",
                    locale == null || locale.isEmpty() ? "REQUIRED" : "UNKNOWN_VALUE"));
        }
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors);
        }
        return transactions.run(() -> {
            if (cleanName != null || locale != null) {
                accounts.updateProfile(me.id(), cleanName, locale, now());
            }
            return get(me);
        });
    }

    /**
     * Changes the caller's password and ends every session of the account (S1A-AC-17, S1A-AC-02); the
     * caller's session is started again by {@link SignInFlow}. A new password that breaks AUTH-5 is 400
     * before the current one is looked at. A wrong current password is 400 and counts; after five in a
     * row every try is 429 for 15 minutes (S1A-AC-18). The change leaves an audit row whose person and
     * target are the account, without either password (S1A-AC-62).
     */
    void changePassword(CurrentAccount me, String currentPassword, String newPassword) {
        AccountRow account = row(me);
        Instant asked = now();
        if (account.lockedUntil() != null && asked.isBefore(account.lockedUntil())) {
            throw ApiException.rateLimited(Duration.between(asked, account.lockedUntil()));
        }
        List<ApiError.FieldError> errors = new ArrayList<>();
        if (currentPassword == null || currentPassword.isEmpty()) {
            errors.add(new ApiError.FieldError("currentPassword", "REQUIRED"));
        }
        PasswordRules.broken(newPassword, account.email())
                .ifPresent(rule -> errors.add(new ApiError.FieldError("newPassword", rule)));
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors);
        }
        if (!PasswordHashes.usable(account.passwordHash())
                || !passwords.matches(currentPassword, account.passwordHash())) {
            boolean locked = transactions.run(() -> {
                Instant at = now();
                if (accounts.updateFailedSignIns(account.id(), at) < SignInService.MAX_FAILURES) {
                    return false;
                }
                accounts.updateLockedUntil(account.id(), at.plus(SignInService.LOCK), at);
                return true;
            });
            log.info("Account {} gave a wrong current password{}.", account.id(),
                    locked ? "; the account is locked for 15 minutes" : "");
            throw wrongCurrentPassword();
        }
        String hash = passwords.encode(newPassword);
        boolean changed = transactions.run(() -> {
            // Under the row's lock, the password that matched is still the account's.
            AccountRow current = accounts.lockById(account.id()).orElse(null);
            if (current == null || !account.passwordHash().equals(current.passwordHash())) {
                return false;
            }
            accounts.updatePassword(account.id(), hash, now());
            sessions.endAll(account.id());
            audit.record(me.actor(), AuditAction.PASSWORD_CHANGED, AccountService.target(account.id()), null);
            return true;
        });
        if (!changed) {
            throw wrongCurrentPassword();
        }
        log.info("Account {} changed its password.", account.id());
    }

    private AccountRow row(CurrentAccount me) {
        return accounts.findById(me.id()).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                ApiErrors.UNAUTHENTICATED, ApiErrors.UNAUTHENTICATED_MESSAGE));
    }

    private static ApiException wrongCurrentPassword() {
        return new ApiException(HttpStatus.BAD_REQUEST, "CURRENT_PASSWORD_WRONG", "The current password is wrong.");
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
