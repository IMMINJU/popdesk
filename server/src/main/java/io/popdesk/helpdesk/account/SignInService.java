package io.popdesk.helpdesk.account;

import io.popdesk.ApiError;
import io.popdesk.ApiException;
import io.popdesk.PublicAddress.App;
import io.popdesk.Transactions;
import io.popdesk.helpdesk.AccountStatus;
import io.popdesk.helpdesk.account.AccountRepository.AccountRow;
import io.popdesk.helpdesk.audit.AuditLog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Sign-in (AUTH-1 to AUTH-3). Every failure gets the same 401 body, whatever the reason (S0-AC-22),
 * and every attempt computes one password hash, also for an email no account has, so the time does
 * not tell either (TM-10). Five wrong passwords in a row lock the account for 15 minutes (S0-AC-23).
 *
 * <p>Audit (S0-AC-83): a sign-in, a failure of an existing account, and a lock each leave a row whose
 * person is that account. A failure is recorded in its own transaction, apart from any change.
 */
@Service
class SignInService {

    static final int MAX_FAILURES = 5;
    static final Duration LOCK = Duration.ofMinutes(15);

    private static final Logger log = LoggerFactory.getLogger(SignInService.class);

    private final AccountRepository accounts;
    private final PasswordEncoder passwords;
    private final SignInRateLimit rateLimit;
    private final AuditLog audit;
    private final Transactions transactions;
    private final Clock clock;
    private final String unusedHash;

    SignInService(AccountRepository accounts, PasswordEncoder passwords, SignInRateLimit rateLimit, AuditLog audit,
            Transactions transactions, Clock clock) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.rateLimit = rateLimit;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock;
        // Compared against when there is no hash to compare, so a failure costs the same time.
        this.unusedHash = passwords.encode("popdesk: no account has this password");
    }

    /** Checks the attempt; returns the account ID that signed in, or throws 400, 401, or 429. */
    long signIn(String email, String password, App app, String clientIp) {
        OptionalLong wait = rateLimit.attempt(clientIp);
        if (wait.isPresent()) {
            throw ApiException.rateLimited(wait.getAsLong());
        }
        List<ApiError.FieldError> errors = new ArrayList<>();
        if (email == null || email.isBlank()) {
            errors.add(new ApiError.FieldError("email", "REQUIRED"));
        }
        if (password == null || password.isEmpty()) {
            errors.add(new ApiError.FieldError("password", "REQUIRED"));
        }
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors);
        }
        Optional<AccountRow> found = accounts.findByEmail(email.strip());
        AccountRow account = found.orElse(null);
        boolean passwordMatches = passwords.matches(password,
                account != null && account.passwordHash() != null ? account.passwordHash() : unusedHash);
        if (account == null) {
            log.info("Sign-in failed: no account has that email.");
            throw failed();
        }
        Instant now = now();
        boolean locked = account.lockedUntil() != null && now.isBefore(account.lockedUntil());
        boolean mayTry = account.status() == AccountStatus.ACTIVE && account.passwordHash() != null
                && SessionFilter.appOf(account.role()) == app;
        if (!mayTry || locked) {
            transactions.run(() -> audit.record(actor(account), "SIGN_IN_FAILED", target(account), null));
            log.info("Sign-in failed for account {}: {}.", account.id(),
                    locked ? "locked" : mayTry ? "wrong password" : "not active or another app's account");
            throw failed();
        }
        if (!passwordMatches) {
            boolean nowLocked = transactions.run(() -> {
                Instant at = now();
                int failures = accounts.updateFailedSignIns(account.id(), at);
                audit.record(actor(account), "SIGN_IN_FAILED", target(account), null);
                if (failures < MAX_FAILURES) {
                    return false;
                }
                Instant until = at.plus(LOCK);
                accounts.updateLockedUntil(account.id(), until, at);
                Map<String, Object> changes = new LinkedHashMap<>();
                changes.put("lockedUntil", until);
                audit.record(actor(account), "ACCOUNT_LOCKED", target(account), changes);
                return true;
            });
            log.info("Sign-in failed for account {}: wrong password{}.", account.id(),
                    nowLocked ? "; the account is locked for 15 minutes" : "");
            throw failed();
        }
        transactions.run(() -> {
            accounts.updateClearFailures(account.id(), now());
            audit.record(actor(account), "SIGN_IN_SUCCEEDED", target(account), null);
        });
        log.info("Account {} signed in to the {}.", account.id(), app == App.PORTAL ? "portal" : "console");
        return account.id();
    }

    private static ApiException failed() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "SIGN_IN_FAILED", "The email or the password is wrong.");
    }

    private static AuditLog.Actor actor(AccountRow account) {
        return new AuditLog.Actor(account.id(), account.name(), account.email());
    }

    private static Map<String, Object> target(AccountRow account) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("type", "ACCOUNT");
        target.put("accountId", account.id());
        return target;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
