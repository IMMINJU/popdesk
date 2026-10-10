package io.popdesk.helpdesk.account;

import io.popdesk.InstallationLocale;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * The step after the migrations and before the ports open (architecture.md section 7): make team 1,
 * then the first admin from {@code POPDESK_ADMIN_EMAIL} and {@code POPDESK_ADMIN_PASSWORD} if the
 * database has no admin (ACCT-5).
 *
 * <p>When that cannot be done (a value is missing, the password breaks AUTH-5, or the insert fails)
 * the app does not stop: it logs what to set, readiness stays down, and business requests get 503
 * {@code SETUP_REQUIRED} (S0-AC-02). The process keeps running so a container is not restarted again
 * and again. With an admin in the database the two values are not read at all (S0-AC-03).
 *
 * <p>A {@code POPDESK_LOCALE} that is not a language popdesk has ({@link InstallationLocale}) ends the
 * same way, whether the database has an admin or not: accounts and messages would get a language no
 * text exists for.
 */
@Component
public class FirstAdminSetup implements SmartInitializingSingleton {

    static final String EMAIL_KEY = "POPDESK_ADMIN_EMAIL";
    static final String PASSWORD_KEY = "POPDESK_ADMIN_PASSWORD";

    /** The first admin's display name; there is no setting for it. The admin can change it later. */
    static final String ADMIN_NAME = "Admin";

    private static final Logger log = LoggerFactory.getLogger(FirstAdminSetup.class);

    private final FirstAdminService service;
    private final PasswordEncoder passwords;
    private final Environment environment;
    private final InstallationLocale locale;

    private volatile boolean setupRequired = true;

    FirstAdminSetup(FirstAdminService service, PasswordEncoder passwords, Environment environment,
            InstallationLocale locale) {
        this.service = service;
        this.passwords = passwords;
        this.environment = environment;
        this.locale = locale;
    }

    /** Whether the app still waits for a first admin or a valid language and takes no business requests. */
    public boolean setupRequired() {
        return setupRequired;
    }

    @Override
    public void afterSingletonsInstantiated() {
        boolean adminExists;
        try {
            service.createTeamIfMissing();
            adminExists = service.adminExists();
        } catch (DataAccessException | TransactionException e) {
            log.error("Could not read or create team 1 ({}). popdesk takes no requests until it is restarted with a "
                    + "working database.", e.getClass().getSimpleName());
            return;
        }
        if (!locale.valid()) {
            log.error("{} is empty or not a language popdesk has: set it to en or ko, or leave it out for en. "
                    + "popdesk takes no requests and its readiness stays down until this is set and popdesk is "
                    + "restarted.", InstallationLocale.KEY);
            return;
        }
        if (adminExists) {
            setupRequired = false;
            return;
        }
        Optional<String> problem = problem();
        if (problem.isPresent()) {
            log.error("{} popdesk takes no requests and its readiness stays down until this is set and popdesk is "
                    + "restarted.", problem.get());
            return;
        }
        String email = environment.getProperty(EMAIL_KEY).strip();
        String hash = passwords.encode(environment.getProperty(PASSWORD_KEY));
        try {
            service.createAdminIfNone(email, ADMIN_NAME, hash, locale.language());
            setupRequired = false;
            log.info("Created the first admin from {}.", EMAIL_KEY);
        } catch (DataAccessException | TransactionException e) {
            // The exception's message can quote the row, so only its class is logged.
            log.error("Creating the first admin from {} failed ({}). popdesk takes no requests and its readiness "
                    + "stays down; check the database and restart.", EMAIL_KEY, e.getClass().getSimpleName());
        }
    }

    /** What is wrong with the first admin values, as a log sentence naming the keys; empty when nothing is. */
    private Optional<String> problem() {
        String email = environment.getProperty(EMAIL_KEY);
        String password = environment.getProperty(PASSWORD_KEY);
        List<String> missing = new ArrayList<>();
        if (email == null || email.isBlank()) {
            missing.add(EMAIL_KEY);
        }
        if (password == null || password.isEmpty()) {
            missing.add(PASSWORD_KEY);
        }
        if (!missing.isEmpty()) {
            return Optional.of("The database has no admin, and " + String.join(" and ", missing)
                    + (missing.size() == 1 ? " is" : " are") + " not set. Set " + EMAIL_KEY + " and " + PASSWORD_KEY
                    + " (or " + PASSWORD_KEY + "_FILE) to create the first admin.");
        }
        String trimmed = email.strip();
        int at = trimmed.indexOf('@');
        if (at <= 0 || at == trimmed.length() - 1) {
            return Optional.of("The database has no admin, and " + EMAIL_KEY + " is not an email address.");
        }
        return PasswordRules.broken(password, trimmed).map(rule -> "The database has no admin, and "
                + PASSWORD_KEY + " breaks the password rules (" + rule + "): " + PasswordRules.MIN_LENGTH + " to "
                + PasswordRules.MAX_LENGTH + " characters, not only whitespace, and not the same as " + EMAIL_KEY
                + ".");
    }
}
