package io.popdesk.helpdesk.account;

import io.popdesk.PublicAddress.App;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Started OIDC sign-ins (table {@code oidc_request}, AUTH-6, D-64): each used once within 10 minutes. The table keeps
 * the SHA-256 of the {@code state}, the nonce, and the browser value ({@link Tokens#hash}), never the values.
 */
@Repository
class OidcRequestRepository {

    /** One sign-in request, as the callback takes it. */
    record RequestRow(String browserHash, String nonceHash, String codeVerifier, App app, String nextPath,
            Instant createdAt) {}

    private final JdbcClient jdbc;

    OidcRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(String stateHash, String browserHash, String nonceHash, String codeVerifier, App app, String nextPath,
            Instant now) {
        jdbc.sql("insert into oidc_request (tenant_id, state_hash, browser_hash, nonce_hash, code_verifier, app, "
                        + "next_path, created_at) values (1, ?, ?, ?, ?, ?, ?, ?)")
                .params(stateHash, browserHash, nonceHash, codeVerifier, app.name(), nextPath, Timestamp.from(now))
                .update();
    }

    /** Deletes the requests made at or before {@code before} (S11-AC-14). */
    int deleteMadeBefore(Instant before) {
        return jdbc.sql("delete from oidc_request where created_at <= ?").param(Timestamp.from(before)).update();
    }

    /** How many requests were made after {@code after}: those that may still be used (S11-AC-08). */
    long countMadeAfter(Instant after) {
        return jdbc.sql("select count(*) from oidc_request where created_at > ?").param(Timestamp.from(after))
                .query(Long.class).single();
    }

    /**
     * Deletes the request of this state hash and returns it, in one statement: of two callbacks with the same state,
     * one gets it and the other nothing (S11-AC-09). Empty when there is none.
     */
    Optional<RequestRow> take(String stateHash) {
        return jdbc.sql("delete from oidc_request where state_hash = ? returning browser_hash, nonce_hash, "
                        + "code_verifier, app, next_path, created_at")
                .param(stateHash)
                .query((rs, n) -> new RequestRow(rs.getString("browser_hash"), rs.getString("nonce_hash"),
                        rs.getString("code_verifier"), App.valueOf(rs.getString("app")), rs.getString("next_path"),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }
}
