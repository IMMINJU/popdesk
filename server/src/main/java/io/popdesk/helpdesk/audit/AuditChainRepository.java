package io.popdesk.helpdesk.audit;

import io.popdesk.helpdesk.audit.AuditChain.Values;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Writes to the audit log's hash chain on a plain JDBC connection: the one place that inserts into
 * {@code audit_log} (slice 1b premortem 3). The app calls it with the connection of its transaction
 * ({@link AuditRepository#append}), the commands in the container with their own (S1B-AC-43), so
 * every entry gets its place and hash the same way. Nothing here changes or removes a row.
 */
public final class AuditChainRepository {

    private AuditChainRepository() {}

    /**
     * Appends entries to the chain in the caller's transaction, in the order given, and returns their
     * IDs in that order. The connection must not be in auto-commit mode: the lock on the chain's head
     * row has to last until the caller commits, and the caller should commit right after this.
     *
     * <p>Order of work (architecture.md "동시성과 잠금 순서" 5): the JSON goes to the database and comes
     * back as {@code jsonb} writes it, in one statement for all entries; then the head row is locked
     * {@code FOR NO KEY UPDATE}; then the hashes are computed, the rows inserted, and the head moved.
     * Nothing else happens while the head row is held.
     */
    public static List<Long> append(Connection c, List<Values> entries) throws SQLException {
        if (entries.isEmpty()) {
            return List.of();
        }
        List<Values> stored = asStored(c, entries);
        long seq;
        String prevHash;
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("select head_seq, head_hash from audit_chain for no key update")) {
            if (!rs.next()) {
                throw new IllegalStateException("The audit chain has no head row");
            }
            seq = rs.getLong(1);
            prevHash = rs.getString(2);
        }
        int n = stored.size();
        Long[] seqs = new Long[n];
        String[] prevHashes = new String[n];
        String[] hashes = new String[n];
        for (int i = 0; i < n; i++) {
            seqs[i] = ++seq;
            prevHashes[i] = prevHash;
            prevHash = AuditChain.hash(prevHash, stored.get(i));
            hashes[i] = prevHash;
        }
        Map<Long, Long> idBySeq = new LinkedHashMap<>();
        try (PreparedStatement s = c.prepareStatement("insert into audit_log (tenant_id, action, actor, target, "
                + "changes, occurred_at, seq, prev_hash, hash) select 1, u.action, cast(u.actor as jsonb), "
                + "cast(u.target as jsonb), cast(u.changes as jsonb), cast(u.occurred as timestamptz), u.seq, "
                + "u.prev_hash, u.hash from unnest(?::text[], ?::text[], ?::text[], ?::text[], ?::text[], "
                + "?::bigint[], ?::text[], ?::text[]) as u(action, actor, target, changes, occurred, seq, prev_hash, "
                + "hash) "
                + "returning seq, id")) {
            setTexts(c, s, 1, stored, Values::action);
            setTexts(c, s, 2, stored, Values::actor);
            setTexts(c, s, 3, stored, Values::target);
            setTexts(c, s, 4, stored, Values::changes);
            setTexts(c, s, 5, stored, v -> CanonicalAuditJson.time(v.occurredAt()));
            s.setArray(6, c.createArrayOf("bigint", seqs));
            s.setArray(7, c.createArrayOf("text", prevHashes));
            s.setArray(8, c.createArrayOf("text", hashes));
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    idBySeq.put(rs.getLong(1), rs.getLong(2));
                }
            }
        }
        List<Long> ids = new ArrayList<>(n);
        for (Long each : seqs) {
            ids.add(idBySeq.get(each));
        }
        // The outside copy (slice 1b, AUDIT-5): when it is on, the send-queue rows for these IDs go in
        // here, after the audit rows and before the head moves. The queue has no foreign key, so
        // nothing else is locked.
        try (PreparedStatement s = c.prepareStatement("update audit_chain set head_seq = ?, head_hash = ?")) {
            s.setLong(1, seq);
            s.setString(2, prevHash);
            s.executeUpdate();
        }
        return ids;
    }

    /** The entries as the database stores them: every JSON text cast to {@code jsonb} and read back. */
    private static List<Values> asStored(Connection c, List<Values> entries) throws SQLException {
        List<Values> stored = new ArrayList<>(entries.size());
        try (PreparedStatement s = c.prepareStatement("select cast(u.actor as jsonb)::text, "
                + "cast(u.target as jsonb)::text, cast(u.changes as jsonb)::text, cast(u.occurred as timestamptz) "
                + "from unnest(?::text[], ?::text[], ?::text[], ?::text[]) with ordinality "
                + "as u(actor, target, changes, occurred, n) order by u.n")) {
            setTexts(c, s, 1, entries, Values::actor);
            setTexts(c, s, 2, entries, Values::target);
            setTexts(c, s, 3, entries, Values::changes);
            setTexts(c, s, 4, entries, v -> CanonicalAuditJson.time(v.occurredAt()));
            try (ResultSet rs = s.executeQuery()) {
                for (Values entry : entries) {
                    if (!rs.next()) {
                        throw new IllegalStateException("The database gave back fewer audit values than it got");
                    }
                    stored.add(new Values(entry.action(), rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getObject(4, OffsetDateTime.class).toInstant()));
                }
            }
        }
        return stored;
    }

    private static void setTexts(Connection c, PreparedStatement s, int index, List<Values> entries,
            Function<Values, String> text) throws SQLException {
        s.setArray(index, c.createArrayOf("text", entries.stream().map(text).toArray(String[]::new)));
    }

    /**
     * Whether this database has the chain (V6 and later). A command in the container can meet an older
     * schema: {@code popdesk restore} of a dump from before the chain, or a command run before the app
     * migrated the database.
     */
    public static boolean exists(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("select to_regclass('audit_chain') is not null")) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    /**
     * Inserts entries into a schema from before the chain, as that version did. V7 gives them their
     * place and hash when the app starts on the database.
     */
    public static void insertBeforeTheChain(Connection c, List<Values> entries) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("insert into audit_log (tenant_id, action, actor, target, "
                + "changes, occurred_at) values (1, ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), "
                + "cast(? as timestamptz))")) {
            for (Values entry : entries) {
                s.setString(1, entry.action());
                s.setString(2, entry.actor());
                s.setString(3, entry.target());
                s.setString(4, entry.changes());
                s.setString(5, CanonicalAuditJson.time(entry.occurredAt()));
                s.executeUpdate();
            }
        }
    }
}
