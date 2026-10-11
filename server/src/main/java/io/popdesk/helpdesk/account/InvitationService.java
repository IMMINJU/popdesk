package io.popdesk.helpdesk.account;

import io.popdesk.ApiError;
import io.popdesk.ApiException;
import io.popdesk.InstallationLocale;
import io.popdesk.PageRequest;
import io.popdesk.Paged;
import io.popdesk.PublicAddress;
import io.popdesk.PublicAddress.App;
import io.popdesk.TextInput;
import io.popdesk.Transactions;
import io.popdesk.helpdesk.AccountStatus;
import io.popdesk.helpdesk.CompanyStatus;
import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.account.AccountRepository.InvitationState;
import io.popdesk.helpdesk.account.AccountRepository.PendingRow;
import io.popdesk.helpdesk.audit.AuditLog;
import io.popdesk.helpdesk.company.CompanyRepository;
import io.popdesk.helpdesk.company.CompanyRepository.CompanyRow;
import io.popdesk.helpdesk.company.CompanyRepository.MembershipRow;
import io.popdesk.notification.InvitationLinks;
import io.popdesk.notification.MailKind;
import io.popdesk.notification.MailQueue;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invitations (INVITE-1 to INVITE-6, S0-AC-33 to S0-AC-40). An invitation makes an {@code INVITED}
 * account, its token, the customer's memberships, the mail queue row, and the audit row in one
 * transaction. The token is 32 random bytes; the database keeps only its SHA-256 (S0-AC-35). It is
 * shown only in the link, in the response when there is no SMTP, and never logged.
 */
@Service
public class InvitationService implements InvitationLinks {

    /** How long a link works (INVITE-2). */
    static final Duration VALIDITY = Duration.ofDays(7);
    static final int EMAIL_MAX = 100;
    static final int NAME_MAX = 50;

    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");

    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);

    /** The invited account, and its link when there is no SMTP to mail it. */
    public record Invited(long accountId, String inviteLink) {}

    /** An entry of the pending list (S0-AC-40). */
    public record Pending(PendingRow row, List<MembershipRow> companies, boolean expired) {}

    private final AccountRepository accounts;
    private final CompanyRepository companies;
    private final MailQueue mail;
    private final AuditLog audit;
    private final PasswordEncoder passwords;
    private final PublicAddress address;
    private final Transactions transactions;
    private final Clock clock;
    private final String locale;

    InvitationService(AccountRepository accounts, CompanyRepository companies, MailQueue mail, AuditLog audit,
            PasswordEncoder passwords, PublicAddress address, Transactions transactions, Clock clock,
            InstallationLocale locale) {
        this.accounts = accounts;
        this.companies = companies;
        this.mail = mail;
        this.audit = audit;
        this.passwords = passwords;
        this.address = address;
        this.transactions = transactions;
        this.clock = clock;
        this.locale = locale.language();
    }

    /**
     * Invites a person (S0-AC-33). Checks the fields, then the companies (400), then whether the email is
     * taken (409, ignoring case and surrounding spaces); a refused invitation leaves no row anywhere.
     */
    public Invited invite(CurrentAccount admin, String email, String name, Role role, List<String> companyCodes) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        String cleanEmail = email(email, errors);
        String cleanName = TextInput.required("name", name, NAME_MAX, errors);
        if (role == null) {
            errors.add(new ApiError.FieldError("role", "REQUIRED"));
        }
        List<String> codes = companyCodes == null ? List.of() : companyCodes;
        if (role == Role.CUSTOMER && codes.isEmpty()) {
            errors.add(new ApiError.FieldError("companyCodes", "REQUIRED"));
        } else if (role != null && role != Role.CUSTOMER && !codes.isEmpty()) {
            errors.add(new ApiError.FieldError("companyCodes", "NOT_ALLOWED"));
        }
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors);
        }
        Set<String> wanted = new LinkedHashSet<>(codes);
        String token = Tokens.create();
        Invited invited = transactions.run(() -> {
            Viewer viewer = admin.viewer();
            List<CompanyRow> found = companies.findByCodes(viewer, wanted);
            if (found.size() != wanted.size() || found.stream().anyMatch(c -> c.status() != CompanyStatus.ACTIVE)) {
                throw ApiException.invalid("companyCodes", "UNKNOWN_VALUE");
            }
            Instant now = now();
            long accountId = accounts.insertInvited(cleanEmail, cleanName, role, locale, now).orElseThrow(() ->
                    ApiException.conflict("EMAIL_TAKEN", "An account has this email."));
            accounts.insertInvitation(accountId, Tokens.hash(token), now, now.plus(VALIDITY));
            for (CompanyRow company : found) {
                companies.insertMember(company.id(), accountId, now);
            }
            mail.queue(MailKind.INVITATION, accountId, null, null);
            Map<String, Object> changes = new LinkedHashMap<>();
            changes.put("email", cleanEmail);
            changes.put("name", cleanName);
            changes.put("role", role.name());
            changes.put("companyCodes", found.stream().map(CompanyRow::code).sorted().toList());
            audit.record(admin.actor(), "INVITATION_CREATED", accountTarget(accountId), changes);
            return new Invited(accountId, mail.enabled() ? null : address.invitationLink(appOf(role), token));
        });
        log.info("Account {} invited as {}.", invited.accountId(), role);
        return invited;
    }

    /**
     * Accepts an invitation and returns the account to sign in (S0-AC-36). Every bad token gets the same
     * 400 (S0-AC-37). A password that breaks AUTH-5 is 400 and leaves the token as it was.
     */
    long accept(String token, String password, App app) {
        String hash = Tokens.hash(token);
        long accountId = transactions.run(() -> {
            PendingRow pending = hash == null ? null : accounts.lockInvitationByTokenHash(hash).orElse(null);
            Instant now = now();
            if (pending == null || !now.isBefore(pending.expiresAt()) || appOf(pending.role()) != app) {
                throw invalidInvitation();
            }
            PasswordRules.broken(password, pending.email()).ifPresent(rule -> {
                throw ApiException.invalid("password", rule);
            });
            // The invitation row stays as the record of the invitation; its token stops working because every
            // lookup by token needs an INVITED account (S0-AC-35).
            accounts.updateActivated(pending.accountId(), passwords.encode(password), now);
            return pending.accountId();
        });
        log.info("Account {} accepted its invitation.", accountId);
        return accountId;
    }

    /**
     * A new link for an account that has not accepted yet (S0-AC-38): a new token, so the previous link
     * stops working, valid for 7 days from now. Only without SMTP (S0-AC-39).
     */
    public String newLink(CurrentAccount admin, long accountId) {
        if (mail.enabled()) {
            throw ApiException.notFound();
        }
        String token = Tokens.create();
        String link = transactions.run(() -> {
            PendingRow pending = accounts.lockInvitation(accountId).orElseThrow(ApiException::notFound);
            Instant expiresAt = now().plus(VALIDITY);
            accounts.updateInvitationToken(accountId, Tokens.hash(token), expiresAt);
            Map<String, Object> changes = new LinkedHashMap<>();
            changes.put("expiresAt", expiresAt);
            audit.record(admin.actor(), "INVITATION_LINK_CREATED", accountTarget(accountId), changes);
            return address.invitationLink(appOf(pending.role()), token);
        });
        log.info("A new invitation link was made for account {}.", accountId);
        return link;
    }

    /**
     * Sends the invitation of an account that has not accepted yet again (S1A-AC-38): a new token, so the
     * previous link stops working, valid for 7 days from now, and a new invitation email. A cancelled
     * invitation is open again and its account {@code INVITED} (S1A-AC-40). An account that accepted
     * before is refused (S1A-AC-41).
     *
     * @return the account, and its link when there is no SMTP to mail it
     */
    public Invited resend(CurrentAccount admin, long accountId) {
        String token = Tokens.create();
        Invited invited = transactions.run(() -> {
            InvitationState state = lockNeverAccepted(accountId);
            Instant now = now();
            Instant expiresAt = now.plus(VALIDITY);
            accounts.updateInvitationToken(accountId, Tokens.hash(token), expiresAt);
            Map<String, Object> changes = new LinkedHashMap<>();
            changes.put("expiresAt", expiresAt);
            if (state.status() != AccountStatus.INVITED) {
                accounts.updateInvitationCancelled(accountId, null);
                accounts.updateStatus(accountId, AccountStatus.INVITED, now);
                changes.put("status", AccountService.change(state.status().name(), AccountStatus.INVITED.name()));
            }
            mail.queue(MailKind.INVITATION, accountId, null, null);
            audit.record(admin.actor(), "INVITATION_RESENT", accountTarget(accountId), changes);
            return new Invited(accountId, mail.enabled() ? null : address.invitationLink(appOf(state.role()), token));
        });
        log.info("The invitation of account {} was sent again.", accountId);
        return invited;
    }

    /**
     * Cancels the invitation of an account that has not accepted yet (S1A-AC-39): the account becomes
     * {@code SUSPENDED}, so its link stops working and an invitation email still queued is not sent. Its
     * memberships and assignments stay. Cancelling again changes nothing and is not audited again. An
     * account that accepted before is refused (S1A-AC-41).
     */
    public void cancel(CurrentAccount admin, long accountId) {
        boolean cancelled = transactions.run(() -> {
            InvitationState state = lockNeverAccepted(accountId);
            if (state.status() != AccountStatus.INVITED) {
                return false;
            }
            Instant now = now();
            accounts.updateInvitationCancelled(accountId, now);
            accounts.updateStatus(accountId, AccountStatus.SUSPENDED, now);
            audit.record(admin.actor(), "INVITATION_CANCELLED", accountTarget(accountId), Map.of("status",
                    AccountService.change(AccountStatus.INVITED.name(), AccountStatus.SUSPENDED.name())));
            return true;
        });
        if (cancelled) {
            log.info("The invitation of account {} was cancelled.", accountId);
        }
    }

    /**
     * The invitation of an account that never accepted, with both rows locked: 404 for an account that
     * does not exist, 400 for one that accepted before, also one suspended since.
     */
    private InvitationState lockNeverAccepted(long accountId) {
        if (accounts.findById(accountId).isEmpty()) {
            throw ApiException.notFound();
        }
        // An account without an invitation row was never invited: the first admin, who has a password.
        InvitationState state = accounts.lockInvitationState(accountId).orElse(null);
        if (state == null || state.accepted()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVITATION_ALREADY_ACCEPTED",
                    "This account accepted its invitation before.");
        }
        return state;
    }

    /**
     * A new token for the invitation email the send loop is about to send (S0-AC-72): the database keeps
     * only token hashes, so the token made at {@link #invite} cannot be mailed. The previous link stops
     * working; the invitation keeps its expiry. Empty when the account has accepted meanwhile.
     */
    @Override
    public Optional<String> renew(long accountId) {
        String token = Tokens.create();
        return transactions.run(() -> accounts.lockInvitation(accountId).map(pending -> {
            accounts.updateInvitationToken(accountId, Tokens.hash(token), pending.expiresAt());
            return address.invitationLink(appOf(pending.role()), token);
        }));
    }

    /**
     * Accounts that have not accepted, newest invitation first, with their companies (S0-AC-40):
     * {@code INVITED} ones and those whose invitation was cancelled (S1A-AC-42).
     */
    @Transactional(readOnly = true)
    public Paged<Pending> pending(CurrentAccount admin, int page, int size) {
        PageRequest request = PageRequest.of(page, size);
        List<PendingRow> rows = accounts.listPending(request.offset(), request.size());
        List<MembershipRow> memberships = companies.listMemberships(admin.viewer(),
                rows.stream().map(PendingRow::accountId).toList());
        Instant now = clock.instant();
        return request.map(rows, accounts.countPending(), row -> new Pending(row,
                memberships.stream().filter(m -> m.accountId() == row.accountId()).toList(),
                !now.isBefore(row.expiresAt())));
    }

    static App appOf(Role role) {
        return SessionFilter.appOf(role);
    }

    private static Map<String, Object> accountTarget(long accountId) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("type", "ACCOUNT");
        target.put("accountId", accountId);
        return target;
    }

    private static String email(String received, List<ApiError.FieldError> errors) {
        if (received == null || received.isBlank()) {
            errors.add(new ApiError.FieldError("email", "REQUIRED"));
            return null;
        }
        String email = TextInput.oneLine(received);
        if (TextInput.length(email) > EMAIL_MAX) {
            errors.add(new ApiError.FieldError("email", "TOO_LONG"));
            return null;
        }
        if (!EMAIL.matcher(email).matches()) {
            errors.add(new ApiError.FieldError("email", "INVALID_FORMAT"));
            return null;
        }
        return email;
    }

    private static ApiException invalidInvitation() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVITATION_INVALID",
                "This invitation link does not work. Ask for a new one.");
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
