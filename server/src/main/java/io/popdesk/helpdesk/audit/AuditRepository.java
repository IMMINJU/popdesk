package io.popdesk.helpdesk.audit;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/**
 * Reads audit rows (table {@code audit_log}) and the chain's one row ({@code audit_chain}). Writing
 * goes through {@link AuditChainRepository}, on the connection of the caller's transaction. Rows are never
 * changed once written; the retention period alone removes them, from the front of the chain.
 */
@Repository
class AuditRepository {

    /** Rows a cursor fetches at a time while an export or the integrity check streams the log. */
    private static final int FETCH_SIZE = 2000;

    private static final String COLUMNS = "id, seq, prev_hash, hash, action, actor::text, target::text, "
            + "changes::text, occurred_at";

    /** The end of the chain and where it starts (the last entry the retention period removed). */
    record Chain(long headSeq, String headHash, long anchorSeq, String anchorHash) {}

    private final JdbcTemplate jdbc;

    AuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Appends to the chain in the current transaction; the IDs of the new rows, in order. */
    List<Long> append(List<AuditChain.Values> entries, boolean copy) {
        return jdbc.execute((ConnectionCallback<List<Long>>) c -> AuditChainRepository.append(c, entries, copy));
    }

    /** One entry by its ID. */
    Optional<AuditRow> find(long id) {
        return jdbc.query("select " + COLUMNS + " from audit_log where id = ?", (rs, n) -> row(rs), id).stream()
                .findFirst();
    }

    /**
     * Locks the chain's row as appending does ({@code FOR NO KEY UPDATE}), so nothing joins the chain
     * until the caller's transaction ends.
     */
    Chain lockChain() {
        return jdbc.queryForObject("select head_seq, head_hash, anchor_seq, anchor_hash from audit_chain "
                + "for no key update", (rs, n) -> new Chain(rs.getLong(1), rs.getString(2), rs.getLong(3),
                rs.getString(4)));
    }

    /**
     * The last entry of the run at the front of the chain in which every entry happened before
     * {@code cutoff}: the entry right before the first one that did not, or the last entry when all
     * did. Empty when the first entry is not that old.
     */
    Optional<AuditRow> lastOfFrontBefore(Instant cutoff) {
        return jdbc.query("select " + COLUMNS + " from audit_log where seq < coalesce((select min(seq) from audit_log "
                + "where occurred_at >= ?), 9223372036854775807) order by seq desc limit 1", (rs, n) -> row(rs),
                cutoff.atOffset(ZoneOffset.UTC)).stream().findFirst();
    }

    /** Deletes the entries up to {@code seq}; returns how many. The retention period's only. */
    long deleteUpTo(long seq) {
        return jdbc.update("delete from audit_log where seq <= ?", seq);
    }

    /** Moves where the chain starts to the last entry removed. */
    void updateAnchor(long seq, String hash) {
        jdbc.update("update audit_chain set anchor_seq = ?, anchor_hash = ?", seq, hash);
    }

    Chain chain() {
        return jdbc.queryForObject("select head_seq, head_hash, anchor_seq, anchor_hash from audit_chain",
                (rs, n) -> new Chain(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4)));
    }

    /** One page, newest first; entries of the same time by {@code seq}, highest first. */
    List<AuditRow> list(AuditFilter filter, long offset, int size) {
        Where where = new Where(filter, null);
        where.args.add(size);
        where.args.add(offset);
        return jdbc.query("select " + COLUMNS + " from audit_log" + where.sql
                + " order by occurred_at desc, seq desc limit ? offset ?", (rs, n) -> row(rs), where.args.toArray());
    }

    /** How many entries the filter finds, among those up to {@code upToSeq} when it is given. */
    long count(AuditFilter filter, Long upToSeq) {
        Where where = new Where(filter, upToSeq);
        Long count = jdbc.queryForObject("select count(*) from audit_log" + where.sql, Long.class,
                where.args.toArray());
        return count == null ? 0 : count;
    }

    /**
     * Hands every entry the filter finds to {@code each} in chain order ({@code seq}, lowest first),
     * read with a cursor. Call it in a transaction: outside one the driver loads every row at once.
     */
    void stream(AuditFilter filter, Long upToSeq, Consumer<AuditRow> each) {
        Where where = new Where(filter, upToSeq);
        jdbc.query(c -> {
            PreparedStatement s = c.prepareStatement("select " + COLUMNS + " from audit_log" + where.sql
                    + " order by seq");
            s.setFetchSize(FETCH_SIZE);
            for (int i = 0; i < where.args.size(); i++) {
                s.setObject(i + 1, where.args.get(i));
            }
            return s;
        }, (RowCallbackHandler) rs -> each.accept(row(rs)));
    }

    private static AuditRow row(ResultSet rs) throws SQLException {
        return new AuditRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getObject(9, OffsetDateTime.class).toInstant());
    }

    /** The {@code where} clause of a filter and its arguments, in order. */
    private static final class Where {

        private final List<String> conditions = new ArrayList<>();
        private final List<Object> args = new ArrayList<>();
        private final String sql;

        Where(AuditFilter filter, Long upToSeq) {
            if (filter.actions() != null) {
                conditions.add("action in (" + "?, ".repeat(filter.actions().size() - 1) + "?)");
                filter.actions().forEach(action -> args.add(action.name()));
            }
            // The JSON values are compared as text: a row changed in the database may hold anything.
            if (filter.actorAccountId() != null) {
                add("actor ->> 'accountId' = ?", filter.actorAccountId().toString());
            }
            if (filter.targetType() != null) {
                add("target ->> 'type' = ?", filter.targetType());
            }
            if (filter.targetAccountId() != null) {
                add("target ->> 'accountId' = ?", filter.targetAccountId().toString());
            }
            if (filter.targetCode() != null) {
                add("target ->> 'code' = ?", filter.targetCode());
            }
            if (filter.from() != null) {
                add("occurred_at >= ?", filter.from().atOffset(ZoneOffset.UTC));
            }
            if (filter.to() != null) {
                add("occurred_at < ?", filter.to().atOffset(ZoneOffset.UTC));
            }
            if (upToSeq != null) {
                add("seq <= ?", upToSeq);
            }
            sql = conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions);
        }

        private void add(String condition, Object arg) {
            conditions.add(condition);
            args.add(arg);
        }
    }
}
