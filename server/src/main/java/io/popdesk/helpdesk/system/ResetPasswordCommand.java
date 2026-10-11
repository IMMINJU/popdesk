package io.popdesk.helpdesk.system;

import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.account.PasswordHashes;
import io.popdesk.helpdesk.account.PasswordRules;
import io.popdesk.helpdesk.audit.AuditAction;
import io.popdesk.helpdesk.system.PopdeskCommand.Failed;
import java.io.Console;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

/**
 * {@code popdesk admin reset-password <email>} (ACCT-6, S0-AC-97): for when no admin can sign in. It
 * needs a terminal and asks twice for the new password without echoing it. The password must keep the
 * password rules (AUTH-5); it is never printed, logged, or put in the audit row. In one transaction it
 * sets the hash (Argon2id as the app does) and the time of the change, which also ends the session of
 * a sign-in that was still running, lifts the sign-in lock, ends the account's sessions, and writes an {@code ADMIN_PASSWORD_RESET} audit row whose person is popdesk itself (account 0).
 *
 * <p>Without a terminal, for an unknown email, for an account that is not {@code ADMIN}, or when the
 * two entries differ or break a rule, it changes nothing and fails. It does not ask again.
 */
final class ResetPasswordCommand {

    private final CommandDatabase database;
    private final Clock clock;

    ResetPasswordCommand(CommandDatabase database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    private record Admin(long id, String email) {}

    void run(String email) throws SQLException {
        Console console = System.console();
        if (console == null || !console.isTerminal()) {
            throw new Failed("This command needs a terminal to ask for the password. Run it without -T: "
                    + "docker compose exec app popdesk admin reset-password <email>");
        }
        try (Connection c = database.connect()) {
            Admin admin = find(c, email.strip());
            char[] first = console.readPassword("New password for %s: ", admin.email());
            char[] second = console.readPassword("Type it again: ");
            if (first == null || second == null || !Arrays.equals(first, second)) {
                throw new Failed("The two passwords differ. Nothing was changed.");
            }
            String password = new String(first);
            Arrays.fill(first, ' ');
            Arrays.fill(second, ' ');
            Optional<String> broken = PasswordRules.broken(password, admin.email());
            if (broken.isPresent()) {
                throw new Failed("The password breaks the password rules (" + broken.get() + "): "
                        + PasswordRules.MIN_LENGTH + " to " + PasswordRules.MAX_LENGTH
                        + " characters, not only spaces, and not the email. Nothing was changed.");
            }
            String hash = PasswordHashes.encoder().encode(password);
            save(c, admin, hash);
            System.out.println("The password of " + admin.email() + " is set. The account is unlocked and its "
                    + "sessions have ended.");
        }
    }

    private static Admin find(Connection c, String email) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("select id, email, role from account where lower(email) = lower(?)")) {
            s.setString(1, email);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) {
                    throw new Failed("No account has this email. Nothing was changed.");
                }
                if (!Role.ADMIN.name().equals(rs.getString("role"))) {
                    throw new Failed("This account is not an admin; this command only resets admin passwords. "
                            + "Nothing was changed.");
                }
                return new Admin(rs.getLong("id"), rs.getString("email"));
            }
        }
    }

    private void save(Connection c, Admin admin, String hash) throws SQLException {
        c.setAutoCommit(false);
        try {
            try (PreparedStatement s = c.prepareStatement("update account set password_hash = ?, failed_sign_ins = 0, "
                    + "locked_until = null, password_changed_at = ?, updated_at = ? where id = ? and role = ?")) {
                Timestamp now = Timestamp.from(clock.instant());
                s.setString(1, hash);
                s.setTimestamp(2, now);
                s.setTimestamp(3, now);
                s.setLong(4, admin.id());
                s.setString(5, Role.ADMIN.name());
                if (s.executeUpdate() != 1) {
                    throw new Failed("The account changed while the command ran. Nothing was changed.");
                }
            }
            try (PreparedStatement s = c.prepareStatement("delete from spring_session where principal_name = ?")) {
                s.setString(1, Long.toString(admin.id()));
                s.executeUpdate();
            }
            database.auditBySystem(c, clock, AuditAction.ADMIN_PASSWORD_RESET,
                    Map.of("type", "ACCOUNT", "accountId", admin.id()), null);
            c.commit();
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        }
    }
}
