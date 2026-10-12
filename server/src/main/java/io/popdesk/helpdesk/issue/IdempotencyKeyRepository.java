package io.popdesk.helpdesk.issue;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The keys of filings (table {@code issue_idempotency}, ISSUE-12). A row is read by the account that
 * wrote it and its key only; the issue it names is then read with the viewer's scope by
 * {@link IssueRepository}.
 */
@Repository
class IdempotencyKeyRepository {

    /** What a key was used for: the hash of the filing and the issue it made. */
    record KeyRow(String requestHash, UUID issueId) {}

    private final JdbcClient jdbc;

    IdempotencyKeyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The account's row for this key, if it was written after {@code after}; an older one no longer counts. */
    Optional<KeyRow> find(long accountId, String key, Instant after) {
        return jdbc.sql("select request_hash, issue_id from issue_idempotency "
                        + "where account_id = ? and key = ? and created_at > ?")
                .params(accountId, key, Timestamp.from(after))
                .query((rs, n) -> new KeyRow(rs.getString("request_hash"), rs.getObject("issue_id", UUID.class)))
                .optional();
    }

    /**
     * Writes the account's row for this key, in the filing transaction, before the issue row it names
     * (the foreign key is checked at commit). A row from {@code staleUntil} or before is taken over: its
     * 24 hours are up, whether or not the cleanup has run. When another filing has the key, this one
     * waits until that one ends, and writes nothing if it was committed (S3-AC-44).
     *
     * @return whether the row is this filing's now; false when a filing that still counts has the key
     */
    boolean insert(long accountId, String key, String requestHash, UUID issueId, Instant now, Instant staleUntil) {
        return jdbc.sql("insert into issue_idempotency (tenant_id, account_id, key, request_hash, issue_id, "
                        + "created_at) values (1, ?, ?, ?, ?, ?) on conflict (account_id, key) do update set "
                        + "request_hash = excluded.request_hash, issue_id = excluded.issue_id, "
                        + "created_at = excluded.created_at where issue_idempotency.created_at <= ?")
                .params(accountId, key, requestHash, issueId, Timestamp.from(now), Timestamp.from(staleUntil))
                .update() == 1;
    }

    /** Deletes the rows written at {@code until} or before; returns how many. */
    long deleteWrittenUntil(Instant until) {
        return jdbc.sql("delete from issue_idempotency where created_at <= ?").param(Timestamp.from(until)).update();
    }
}
