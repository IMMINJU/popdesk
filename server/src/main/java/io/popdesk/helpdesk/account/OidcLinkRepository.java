package io.popdesk.helpdesk.account;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The OIDC links of accounts (table {@code oidc_link}, AUTH-6): an account and a provider's ({@code issuer},
 * {@code subject}). At most one link per pair and one per account and issuer.
 */
@Repository
class OidcLinkRepository {

    private final JdbcClient jdbc;

    OidcLinkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The account linked to this issuer and subject; empty when none is. */
    Optional<Long> findAccount(String issuer, String subject) {
        return jdbc.sql("select account_id from oidc_link where issuer = ? and subject = ?")
                .params(issuer, subject).query(Long.class).optional();
    }

    /** The subject the account is linked to at this issuer; empty when it is not. */
    Optional<String> findSubject(long accountId, String issuer) {
        return jdbc.sql("select subject from oidc_link where account_id = ? and issuer = ?")
                .params(accountId, issuer).query(String.class).optional();
    }

    /**
     * Links the account; false, with nothing written, when the pair is linked already or the account is linked to
     * another subject at this issuer (either unique constraint).
     */
    boolean insert(long accountId, String issuer, String subject, Instant now) {
        return jdbc.sql("insert into oidc_link (tenant_id, account_id, issuer, subject, linked_at) "
                        + "values (1, ?, ?, ?, ?) on conflict do nothing")
                .params(accountId, issuer, subject, Timestamp.from(now)).update() == 1;
    }

    /** Deletes every link of the account (S11-AC-27, S11-AC-29); returns how many there were. */
    int deleteOf(long accountId) {
        return jdbc.sql("delete from oidc_link where account_id = ?").param(accountId).update();
    }
}
