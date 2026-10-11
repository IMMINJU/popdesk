package io.popdesk.helpdesk.account;

import io.popdesk.ApiException;
import io.popdesk.PublicAddress;
import io.popdesk.PublicAddress.App;
import io.popdesk.Transactions;
import io.popdesk.helpdesk.AccountStatus;
import io.popdesk.helpdesk.account.AccountRepository.AccountRow;
import io.popdesk.helpdesk.account.PasswordResetRepository.ResetRow;
import io.popdesk.helpdesk.audit.AuditLog;
import io.popdesk.notification.MailKind;
import io.popdesk.notification.MailQueue;
import io.popdesk.notification.PasswordResetLinks;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Password reset links (AUTH-7, S1A-AC-05 to S1A-AC-15). A request makes a link for an {@code ACTIVE}
 * account of the app it came to and queues its email in one transaction; any other request does
 * nothing, and both get the same answer (S1A-AC-05). The token is 32 random bytes; the database keeps
 * only its SHA-256 (S1A-AC-08). It is shown only in the link and never logged (S1A-AC-14). The tokens
 * have a table of their own, so an invitation token is no reset token and the reverse (S1A-AC-83).
 *
 * <p>Every change here first locks the account's row, so two of them for one account run one after
 * the other.
 */
@Service
public class PasswordResetService implements PasswordResetLinks {

    /** How long a link works (AUTH-7). */
    static final Duration VALIDITY = Duration.ofHours(1);

    /** A request this soon after the account's last link makes no new one (S1A-AC-12). */
    static final Duration REQUEST_INTERVAL = Duration.ofMinutes(15);

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final AccountRepository accounts;
    private final PasswordResetRepository resets;
    private final Sessions sessions;
    private final MailQueue mail;
    private final AuditLog audit;
    private final PasswordEncoder passwords;
    private final PublicAddress address;
    private final Transactions transactions;
    private final Clock clock;
    private final IpRateLimit rateLimit;

    PasswordResetService(AccountRepository accounts, PasswordResetRepository resets, Sessions sessions, MailQueue mail,
            AuditLog audit, PasswordEncoder passwords, PublicAddress address, Transactions transactions, Clock clock) {
        this.accounts = accounts;
        this.resets = resets;
        this.sessions = sessions;
        this.mail = mail;
        this.audit = audit;
        this.passwords = passwords;
        this.address = address;
        this.transactions = transactions;
        this.clock = clock;
        this.rateLimit = new IpRateLimit(clock);
    }

    /**
     * Takes a request for a reset email (S1A-AC-05 to S1A-AC-07). An email goes out only to an
     * {@code ACTIVE} account of {@code app}, and not within 15 minutes of the account's last link, which
     * then keeps working (S1A-AC-12). The request changes nothing else about the account (S1A-AC-85).
     * Throws 400 for an empty email and 429 over the limit per IP (S1A-AC-13); otherwise it returns the
     * same way whatever it did.
     */
    void request(String email, App app, String clientIp) {
        rateLimit.attempt(clientIp).ifPresent(left -> {
            throw ApiException.rateLimited(left);
        });
        if (email == null || email.isBlank()) {
            throw ApiException.invalid("email", "REQUIRED");
        }
        AccountRow found = accounts.findByEmail(email.strip()).filter(a -> mayReset(a, app)).orElse(null);
        if (found == null) {
            log.info("A password reset was asked for an email that gets none.");
            return;
        }
        boolean queued = transactions.run(() -> {
            AccountRow account = accounts.lockById(found.id()).filter(a -> mayReset(a, app)).orElse(null);
            if (account == null) {
                return false;
            }
            Instant now = now();
            Optional<ResetRow> last = resets.findByAccountId(account.id());
            if (last.isPresent() && now.isBefore(last.get().createdAt().plus(REQUEST_INTERVAL))) {
                return false;
            }
            resets.upsert(account.id(), Tokens.hash(Tokens.create()), now, now.plus(VALIDITY));
            mail.queue(MailKind.PASSWORD_RESET, account.id(), null, null);
            return true;
        });
        log.info(queued ? "A password reset email was queued for account {}."
                : "A password reset was asked for account {} again; nothing was queued.", found.id());
    }

    /**
     * Sets the password with a link's token (S1A-AC-09): the lock of wrong passwords is lifted and every
     * session of the account ends (S1A-AC-04). Every token that does not count gets the same 400
     * (S1A-AC-10, S1A-AC-84). A password that breaks AUTH-5 is 400 and leaves the token as it was
     * (S1A-AC-11). The new password leaves an audit row whose person and target are the account, without
     * the token or the password (S1A-AC-62); a request for a link leaves none.
     */
    void confirm(String token, String password, App app) {
        if (token == null || token.isEmpty()) {
            throw ApiException.invalid("token", "REQUIRED");
        }
        String hash = Tokens.hash(token);
        long accountId = transactions.run(() -> {
            // Without the lock first, to learn whose row to lock; then again under it.
            Long owner = hash == null ? null : resets.findByTokenHash(hash).map(ResetRow::accountId).orElse(null);
            AccountRow account = owner == null ? null : accounts.lockById(owner).orElse(null);
            ResetRow reset = account == null ? null : resets.findByTokenHash(hash).orElse(null);
            Instant now = now();
            if (reset == null || reset.accountId() != account.id() || reset.usedAt() != null
                    || !now.isBefore(reset.expiresAt()) || !mayReset(account, app)) {
                throw invalidToken();
            }
            PasswordRules.broken(password, account.email()).ifPresent(rule -> {
                throw ApiException.invalid("password", rule);
            });
            accounts.updatePassword(account.id(), passwords.encode(password), now);
            resets.updateUsed(account.id(), now);
            sessions.endAll(account.id());
            audit.record(new AuditLog.Actor(account.id(), account.name(), account.email()),
                    "PASSWORD_RESET_COMPLETED", AccountService.target(account.id()), null);
            return account.id();
        });
        log.info("Account {} set a new password with a reset link.", accountId);
    }

    /**
     * Makes a new link for the account whatever the time since its last one, and queues its email; the
     * link it had stops working. Without SMTP nothing can be mailed, and the link is returned to be shown
     * once. For a reset that is not the account's own request (an admin's, S1A-AC-37). Call it in the
     * transaction that holds the lock of the account's row ({@link AccountRepository#lockById}).
     *
     * @return the link when there is no SMTP to mail it; empty when its email is queued
     */
    public Optional<String> issue(AccountRow account) {
        Instant now = now();
        String token = Tokens.create();
        resets.upsert(account.id(), Tokens.hash(token), now, now.plus(VALIDITY));
        if (!mail.enabled()) {
            return Optional.of(address.passwordResetLink(SessionFilter.appOf(account.role()), token));
        }
        mail.queue(MailKind.PASSWORD_RESET, account.id(), null, null);
        return Optional.empty();
    }

    /**
     * A new token for the reset email the send loop is about to send: the database keeps only token
     * hashes, so the token made with the link cannot be mailed. The link keeps its expiry. Empty when the
     * link is used or expired, or the account is no longer {@code ACTIVE}.
     */
    @Override
    public Optional<String> renew(long accountId) {
        String token = Tokens.create();
        return transactions.run(() -> {
            AccountRow account = accounts.lockById(accountId).filter(a -> a.status() == AccountStatus.ACTIVE)
                    .orElse(null);
            ResetRow reset = account == null ? null : resets.findByAccountId(accountId).orElse(null);
            if (reset == null || reset.usedAt() != null || !now().isBefore(reset.expiresAt())) {
                return Optional.empty();
            }
            resets.updateTokenHash(accountId, Tokens.hash(token));
            return Optional.of(address.passwordResetLink(SessionFilter.appOf(account.role()), token));
        });
    }

    /** Whether the account may reset its password on this app's host: {@code ACTIVE}, and of that app. */
    private static boolean mayReset(AccountRow account, App app) {
        return account.status() == AccountStatus.ACTIVE && SessionFilter.appOf(account.role()) == app;
    }

    private static ApiException invalidToken() {
        return new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_RESET_INVALID",
                "This password reset link does not work. Ask for a new one.");
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
