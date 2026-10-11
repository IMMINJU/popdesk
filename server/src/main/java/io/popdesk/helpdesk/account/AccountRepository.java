package io.popdesk.helpdesk.account;

import io.popdesk.helpdesk.AccountStatus;
import io.popdesk.helpdesk.Role;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Accounts and their open invitations (tables {@code account} and {@code invitation}). Emails are
 * compared without case, as the unique index {@code account_email_key} does (AUTH-1).
 */
@Repository
public class AccountRepository {

    /**
     * One account row. {@code passwordHash} is null until an invitation is accepted;
     * {@code passwordChangedAt} is null for a password that never changed.
     */
    public record AccountRow(long id, String email, String name, Role role, AccountStatus status, String passwordHash,
            String locale, int failedSignIns, Instant lockedUntil, Instant passwordChangedAt, Instant createdAt) {}

    /** An {@code INVITED} account with its open invitation. */
    public record PendingRow(long accountId, String email, String name, Role role, Instant invitedAt,
            Instant expiresAt) {}

    private static final String ACCOUNT_COLUMNS = "a.id, a.email, a.name, a.role, a.status, a.password_hash, a.locale, "
            + "a.failed_sign_ins, a.locked_until, a.password_changed_at, a.created_at";

    private final JdbcClient jdbc;

    AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AccountRow> findById(long id) {
        return jdbc.sql("select " + ACCOUNT_COLUMNS + " from account a where a.id = ?")
                .param(id).query(AccountRepository::account).optional();
    }

    public Optional<AccountRow> findByEmail(String email) {
        return jdbc.sql("select " + ACCOUNT_COLUMNS + " from account a where lower(a.email) = lower(?)")
                .param(email).query(AccountRepository::account).optional();
    }

    /**
     * The account row, locked until the end of the transaction. A change that depends on what the row
     * says (its password, its status) takes this lock before it reads anything else.
     */
    public Optional<AccountRow> lockById(long id) {
        return jdbc.sql("select " + ACCOUNT_COLUMNS + " from account a where a.id = ? for no key update")
                .param(id).query(AccountRepository::account).optional();
    }

    /** A new {@code INVITED} account; empty when an account has this email, ignoring case (409). */
    Optional<Long> insertInvited(String email, String name, Role role, String locale, Instant now) {
        return jdbc.sql("insert into account (tenant_id, email, name, role, status, locale, created_at, updated_at) "
                        + "values (1, ?, ?, ?, ?, ?, ?, ?) on conflict ((lower(email))) do nothing returning id")
                .params(email, name, role.name(), AccountStatus.INVITED.name(), locale, Timestamp.from(now),
                        Timestamp.from(now))
                .query(Long.class).optional();
    }

    /** One more wrong password; returns the new count. */
    int updateFailedSignIns(long id, Instant now) {
        return jdbc.sql("update account set failed_sign_ins = failed_sign_ins + 1, updated_at = ? where id = ? "
                        + "returning failed_sign_ins")
                .params(Timestamp.from(now), id).query(Integer.class).single();
    }

    /** Locks the account until {@code until} and starts the count of wrong passwords again. */
    void updateLockedUntil(long id, Instant until, Instant now) {
        jdbc.sql("update account set locked_until = ?, failed_sign_ins = 0, updated_at = ? where id = ?")
                .params(Timestamp.from(until), Timestamp.from(now), id).update();
    }

    /** After a successful sign-in: no wrong passwords, no lock. Writes only when there is something to clear. */
    void updateClearFailures(long id, Instant now) {
        jdbc.sql("update account set failed_sign_ins = 0, locked_until = null, updated_at = ? "
                        + "where id = ? and (failed_sign_ins <> 0 or locked_until is not null)")
                .params(Timestamp.from(now), id).update();
    }

    /** Accepting an invitation: the password hash, and the account becomes {@code ACTIVE}. */
    void updateActivated(long id, String passwordHash, Instant now) {
        jdbc.sql("update account set password_hash = ?, status = ?, failed_sign_ins = 0, locked_until = null, "
                        + "updated_at = ? where id = ?")
                .params(passwordHash, AccountStatus.ACTIVE.name(), Timestamp.from(now), id).update();
    }

    /**
     * A new password, set by the account or with a reset link: no wrong passwords, no lock, and the time
     * of the change, which ends the sessions of earlier sign-ins ({@link SessionFilter}).
     */
    void updatePassword(long id, String passwordHash, Instant now) {
        jdbc.sql("update account set password_hash = ?, failed_sign_ins = 0, locked_until = null, "
                        + "password_changed_at = ?, updated_at = ? where id = ?")
                .params(passwordHash, Timestamp.from(now), Timestamp.from(now), id).update();
    }

    /** The account's own name and language; a null value keeps what the row has. */
    void updateProfile(long id, String name, String locale, Instant now) {
        jdbc.sql("update account set name = coalesce(?, name), locale = coalesce(?, locale), updated_at = ? "
                        + "where id = ?")
                .params(name, locale, Timestamp.from(now), id).update();
    }

    List<AccountRow> list(Role role, long offset, int limit) {
        return jdbc.sql("select " + ACCOUNT_COLUMNS + " from account a where (cast(? as text) is null or a.role = ?) "
                        + "order by a.created_at desc, a.id desc offset ? limit ?")
                .params(role == null ? null : role.name(), role == null ? null : role.name(), offset, limit)
                .query(AccountRepository::account).list();
    }

    long count(Role role) {
        return jdbc.sql("select count(*) from account a where (cast(? as text) is null or a.role = ?)")
                .params(role == null ? null : role.name(), role == null ? null : role.name())
                .query(Long.class).single();
    }

    // Invitations

    void insertInvitation(long accountId, String tokenHash, Instant invitedAt, Instant expiresAt) {
        jdbc.sql("insert into invitation (account_id, tenant_id, token_hash, invited_at, expires_at) "
                        + "values (?, 1, ?, ?, ?)")
                .params(accountId, tokenHash, Timestamp.from(invitedAt), Timestamp.from(expiresAt)).update();
    }

    /** The open invitation with this token hash, and its account, both locked; empty when there is none. */
    Optional<PendingRow> lockInvitationByTokenHash(String tokenHash) {
        return jdbc.sql("select a.id, a.email, a.name, a.role, i.invited_at, i.expires_at from invitation i "
                        + "join account a on a.id = i.account_id where i.token_hash = ? and a.status = ? "
                        + "for no key update")
                .params(tokenHash, AccountStatus.INVITED.name()).query(AccountRepository::pending).optional();
    }

    /** The open invitation of an {@code INVITED} account, locked; empty when there is none. */
    Optional<PendingRow> lockInvitation(long accountId) {
        return jdbc.sql("select a.id, a.email, a.name, a.role, i.invited_at, i.expires_at from invitation i "
                        + "join account a on a.id = i.account_id where i.account_id = ? and a.status = ? "
                        + "for no key update")
                .params(accountId, AccountStatus.INVITED.name()).query(AccountRepository::pending).optional();
    }

    /** A new token for the invitation; the old one stops working. */
    void updateInvitationToken(long accountId, String tokenHash, Instant expiresAt) {
        jdbc.sql("update invitation set token_hash = ?, expires_at = ? where account_id = ?")
                .params(tokenHash, Timestamp.from(expiresAt), accountId).update();
    }

    /** {@code INVITED} accounts with an open invitation, newest invitation first. */
    List<PendingRow> listPending(long offset, int limit) {
        return jdbc.sql("select a.id, a.email, a.name, a.role, i.invited_at, i.expires_at from invitation i "
                        + "join account a on a.id = i.account_id where a.status = ? "
                        + "order by i.invited_at desc, a.id desc offset ? limit ?")
                .params(AccountStatus.INVITED.name(), offset, limit).query(AccountRepository::pending).list();
    }

    long countPending() {
        return jdbc.sql("select count(*) from invitation i join account a on a.id = i.account_id where a.status = ?")
                .param(AccountStatus.INVITED.name()).query(Long.class).single();
    }

    private static AccountRow account(ResultSet rs, int row) throws SQLException {
        return new AccountRow(rs.getLong("id"), rs.getString("email"), rs.getString("name"),
                Role.valueOf(rs.getString("role")), AccountStatus.valueOf(rs.getString("status")),
                rs.getString("password_hash"), rs.getString("locale"), rs.getInt("failed_sign_ins"),
                instant(rs.getTimestamp("locked_until")), instant(rs.getTimestamp("password_changed_at")),
                instant(rs.getTimestamp("created_at")));
    }

    private static PendingRow pending(ResultSet rs, int row) throws SQLException {
        return new PendingRow(rs.getLong("id"), rs.getString("email"), rs.getString("name"),
                Role.valueOf(rs.getString("role")), instant(rs.getTimestamp("invited_at")),
                instant(rs.getTimestamp("expires_at")));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
