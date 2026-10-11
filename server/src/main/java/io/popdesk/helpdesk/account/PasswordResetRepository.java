package io.popdesk.helpdesk.account;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Password reset links (table {@code password_reset}): one row per account, replaced by each new link.
 * Every write but the cleanup's is made by a transaction that holds the lock of the account's row
 * ({@link AccountRepository#lockById}), so a row read under that lock stays as read.
 */
@Repository
class PasswordResetRepository {

    /** The reset link of an account. {@code usedAt} is null until its token set a password. */
    record ResetRow(long accountId, Instant createdAt, Instant expiresAt, Instant usedAt) {}

    private static final String COLUMNS = "r.account_id, r.created_at, r.expires_at, r.used_at";

    private final JdbcClient jdbc;

    PasswordResetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<ResetRow> findByAccountId(long accountId) {
        return jdbc.sql("select " + COLUMNS + " from password_reset r where r.account_id = ?")
                .param(accountId).query(PasswordResetRepository::reset).optional();
    }

    Optional<ResetRow> findByTokenHash(String tokenHash) {
        return jdbc.sql("select " + COLUMNS + " from password_reset r where r.token_hash = ?")
                .param(tokenHash).query(PasswordResetRepository::reset).optional();
    }

    /** The account's link, made now and not used; the link the account had stops working. */
    void upsert(long accountId, String tokenHash, Instant now, Instant expiresAt) {
        jdbc.sql("insert into password_reset (account_id, tenant_id, token_hash, created_at, expires_at) "
                        + "values (?, 1, ?, ?, ?) on conflict (account_id) do update set "
                        + "token_hash = excluded.token_hash, created_at = excluded.created_at, "
                        + "expires_at = excluded.expires_at, used_at = null")
                .params(accountId, tokenHash, Timestamp.from(now), Timestamp.from(expiresAt)).update();
    }

    /** A new token for the link; the old one stops working. */
    void updateTokenHash(long accountId, String tokenHash) {
        jdbc.sql("update password_reset set token_hash = ? where account_id = ?")
                .params(tokenHash, accountId).update();
    }

    void updateUsed(long accountId, Instant now) {
        jdbc.sql("update password_reset set used_at = ? where account_id = ?")
                .params(Timestamp.from(now), accountId).update();
    }

    /** Deletes the links that were used or have expired by {@code now}; returns how many. */
    long deleteEnded(Instant now) {
        return jdbc.sql("delete from password_reset where used_at is not null or expires_at <= ?")
                .param(Timestamp.from(now)).update();
    }

    private static ResetRow reset(ResultSet rs, int row) throws SQLException {
        Timestamp usedAt = rs.getTimestamp("used_at");
        return new ResetRow(rs.getLong("account_id"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(), usedAt == null ? null : usedAt.toInstant());
    }
}
