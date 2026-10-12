package io.popdesk.helpdesk.account;

import io.popdesk.helpdesk.AccountStatus;
import io.popdesk.helpdesk.Role;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Accounts and their invitations (tables {@code account} and {@code invitation}). Emails are
 * compared without case, as the unique index {@code account_email_key} does (AUTH-1).
 */
@Repository
public class AccountRepository {

    /**
     * One account row. {@code passwordHash} is null until an invitation is accepted;
     * {@code passwordChangedAt} is null for a password that never changed; {@code photoUpdatedAt} is null
     * for an account without a profile photo.
     */
    public record AccountRow(long id, String email, String name, Role role, AccountStatus status, String passwordHash,
            String locale, int failedSignIns, Instant lockedUntil, Instant passwordChangedAt, Instant createdAt,
            Instant photoUpdatedAt) {}

    /**
     * An account that has not accepted its invitation, with the invitation. {@code cancelled}: an admin
     * cancelled it, and the account is {@code SUSPENDED} until it is sent again.
     */
    public record PendingRow(long accountId, String email, String name, Role role, Instant invitedAt,
            Instant expiresAt, boolean cancelled) {}

    /** What sending an invitation again or cancelling it depends on. */
    record InvitationState(Role role, AccountStatus status, boolean accepted) {}

    private static final String ACCOUNT_COLUMNS = "a.id, a.email, a.name, a.role, a.status, a.password_hash, a.locale, "
            + "a.failed_sign_ins, a.locked_until, a.password_changed_at, a.created_at, "
            + "(select p.updated_at from account_photo p where p.account_id = a.id) as photo_updated_at";

    private static final String PENDING_COLUMNS = "a.id, a.email, a.name, a.role, i.invited_at, i.expires_at, "
            + "i.cancelled_at";

    /**
     * The list filters, with the parameters of {@link #filter}. The search text is a LIKE pattern in
     * which {@code %} and {@code _} are escaped, so they find only themselves (COM-5, S1A-AC-89). The
     * escape character is named, so the pattern does not lean on the database's default.
     */
    private static final String FILTER = "(cast(? as text) is null or a.role = ?) "
            + "and (cast(? as text) is null or a.status = ?) "
            + "and (cast(? as text) is null or a.name ilike ? escape '\\' "
            + "or a.email ilike ? escape '\\')";

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

    /**
     * The account and every {@code ACTIVE} admin, locked in ID order until the end of the transaction.
     * A suspension or a role change takes these locks before it reads anything, so two of them run one
     * after the other and the second counts the admins the first left (S1A-AC-81). A row another
     * transaction changed meanwhile is read again once that one ends.
     */
    List<AccountRow> lockWithActiveAdmins(long id) {
        return jdbc.sql("select " + ACCOUNT_COLUMNS + " from account a where a.id = ? or (a.role = ? and a.status = ?) "
                        + "order by a.id for no key update")
                .params(id, Role.ADMIN.name(), AccountStatus.ACTIVE.name()).query(AccountRepository::account).list();
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

    void updateName(long id, String name, Instant now) {
        jdbc.sql("update account set name = ?, updated_at = ? where id = ?")
                .params(name, Timestamp.from(now), id).update();
    }

    void updateRole(long id, Role role, Instant now) {
        jdbc.sql("update account set role = ?, updated_at = ? where id = ?")
                .params(role.name(), Timestamp.from(now), id).update();
    }

    void updateStatus(long id, AccountStatus status, Instant now) {
        jdbc.sql("update account set status = ?, updated_at = ? where id = ?")
                .params(status.name(), Timestamp.from(now), id).update();
    }

    /**
     * One page of the accounts with this role and status whose name or email holds {@code q}, ignoring
     * case, newest first; a null filter leaves that one out.
     */
    List<AccountRow> list(Role role, AccountStatus status, String q, long offset, int limit) {
        return jdbc.sql("select " + ACCOUNT_COLUMNS + " from account a where " + FILTER
                        + " order by a.created_at desc, a.id desc offset ? limit ?")
                .params(filter(role, status, q, offset, limit)).query(AccountRepository::account).list();
    }

    long count(Role role, AccountStatus status, String q) {
        return jdbc.sql("select count(*) from account a where " + FILTER)
                .params(filter(role, status, q)).query(Long.class).single();
    }

    /**
     * The LIKE pattern that finds {@code q} anywhere in a value, as it is (COM-5): a backslash before
     * LIKE's wildcards and before its escape, the backslash, makes them ordinary characters.
     */
    public static String containing(String q) {
        return "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static Object[] filter(Role role, AccountStatus status, String q, Object... more) {
        String roleName = role == null ? null : role.name();
        String statusName = status == null ? null : status.name();
        String pattern = q == null ? null : containing(q);
        Object[] filters = {roleName, roleName, statusName, statusName, pattern, pattern, pattern};
        Object[] all = Arrays.copyOf(filters, filters.length + more.length);
        System.arraycopy(more, 0, all, filters.length, more.length);
        return all;
    }

    // Invitations

    void insertInvitation(long accountId, String tokenHash, Instant invitedAt, Instant expiresAt) {
        jdbc.sql("insert into invitation (account_id, tenant_id, token_hash, invited_at, expires_at) "
                        + "values (?, 1, ?, ?, ?)")
                .params(accountId, tokenHash, Timestamp.from(invitedAt), Timestamp.from(expiresAt)).update();
    }

    /** The open invitation with this token hash, and its account, both locked; empty when there is none. */
    Optional<PendingRow> lockInvitationByTokenHash(String tokenHash) {
        return jdbc.sql("select " + PENDING_COLUMNS + " from invitation i "
                        + "join account a on a.id = i.account_id where i.token_hash = ? and a.status = ? "
                        + "for no key update")
                .params(tokenHash, AccountStatus.INVITED.name()).query(AccountRepository::pending).optional();
    }

    /** The open invitation of an {@code INVITED} account, locked; empty when there is none. */
    Optional<PendingRow> lockInvitation(long accountId) {
        return jdbc.sql("select " + PENDING_COLUMNS + " from invitation i "
                        + "join account a on a.id = i.account_id where i.account_id = ? and a.status = ? "
                        + "for no key update")
                .params(accountId, AccountStatus.INVITED.name()).query(AccountRepository::pending).optional();
    }

    /**
     * The invitation of the account and the account, both locked in the order the other invitation locks
     * take them, whatever the account's status; empty when the account was never invited.
     */
    Optional<InvitationState> lockInvitationState(long accountId) {
        return jdbc.sql("select a.role, a.status, a.password_hash is not null as accepted from invitation i "
                        + "join account a on a.id = i.account_id where i.account_id = ? for no key update")
                .param(accountId)
                .query((rs, n) -> new InvitationState(Role.valueOf(rs.getString("role")),
                        AccountStatus.valueOf(rs.getString("status")), rs.getBoolean("accepted")))
                .optional();
    }

    /** A new token for the invitation; the old one stops working. */
    void updateInvitationToken(long accountId, String tokenHash, Instant expiresAt) {
        jdbc.sql("update invitation set token_hash = ?, expires_at = ? where account_id = ?")
                .params(tokenHash, Timestamp.from(expiresAt), accountId).update();
    }

    /** When the invitation was cancelled; null for one that is open (again). */
    void updateInvitationCancelled(long accountId, Instant cancelledAt) {
        jdbc.sql("update invitation set cancelled_at = ? where account_id = ?")
                .params(cancelledAt == null ? null : Timestamp.from(cancelledAt), accountId).update();
    }

    /**
     * Accounts that never accepted their invitation, newest invitation first: {@code INVITED} ones and
     * those whose invitation was cancelled (S1A-AC-42). An account that accepted has a password hash.
     */
    List<PendingRow> listPending(long offset, int limit) {
        return jdbc.sql("select " + PENDING_COLUMNS + " from invitation i "
                        + "join account a on a.id = i.account_id where a.password_hash is null "
                        + "order by i.invited_at desc, a.id desc offset ? limit ?")
                .params(offset, limit).query(AccountRepository::pending).list();
    }

    long countPending() {
        return jdbc.sql("select count(*) from invitation i join account a on a.id = i.account_id "
                        + "where a.password_hash is null")
                .query(Long.class).single();
    }

    private static AccountRow account(ResultSet rs, int row) throws SQLException {
        return new AccountRow(rs.getLong("id"), rs.getString("email"), rs.getString("name"),
                Role.valueOf(rs.getString("role")), AccountStatus.valueOf(rs.getString("status")),
                rs.getString("password_hash"), rs.getString("locale"), rs.getInt("failed_sign_ins"),
                instant(rs.getTimestamp("locked_until")), instant(rs.getTimestamp("password_changed_at")),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("photo_updated_at")));
    }

    private static PendingRow pending(ResultSet rs, int row) throws SQLException {
        return new PendingRow(rs.getLong("id"), rs.getString("email"), rs.getString("name"),
                Role.valueOf(rs.getString("role")), instant(rs.getTimestamp("invited_at")),
                instant(rs.getTimestamp("expires_at")), rs.getTimestamp("cancelled_at") != null);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
