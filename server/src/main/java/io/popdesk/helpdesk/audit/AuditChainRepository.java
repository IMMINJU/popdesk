package io.popdesk.helpdesk.audit;

import io.popdesk.helpdesk.audit.AuditChain.Values;
import io.popdesk.notification.AuditCopyRows;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Writes to the audit log's hash chain on a plain JDBC connection: the one place that inserts into
 * {@code audit_log} (slice 1b premortem 3). The app calls it with the connection of its transaction
 * ({@link AuditRepository#append}), the commands in the container with their own (S1B-AC-43), so
 * every entry gets its place and hash the same way. Nothing here removes a row; the one change to
 * rows already there is the recalculation after an anonymization ({@link #rechain}, ADR D-62).
 */
public final class AuditChainRepository {

    /** Rows of the chain {@link #rechain} reads and writes at a time. */
    private static final int BATCH = 2000;

    private AuditChainRepository() {}

    /**
     * Appends entries to the chain in the caller's transaction, in the order given, and returns their
     * IDs in that order. The connection must not be in auto-commit mode: the lock on the chain's head
     * row has to last until the caller commits, and the caller should commit right after this.
     *
     * <p>Order of work (architecture.md "동시성과 잠금 순서" 5): the JSON goes to the database and comes
     * back as {@code jsonb} writes it, in one statement for all entries; then the head row is locked
     * {@code FOR NO KEY UPDATE}; then the entries that name an anonymized account get its anonymous
     * values; then the hashes are computed, the rows inserted, the send-queue rows of the outside copy
     * inserted when it is on, and the head moved. Nothing else happens while the head row is held.
     *
     * <p>The anonymized accounts are read after the head is locked: an anonymization holds the head
     * until it commits, so an entry whose values were made before it and that joins the chain after it
     * gets the anonymous values too (S10A-AC-35).
     *
     * @param copy whether the outside copy is on ({@link AuditCopyRows#enabled}): then each entry gets
     *     its row in the send queue here, so the entry and its copy are kept or lost together
     *     (S1B-AC-30)
     */
    public static List<Long> append(Connection c, List<Values> entries, boolean copy) throws SQLException {
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
        stored = anonymous(c, stored);
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
        if (copy) {
            // The queue has no foreign key, so nothing else is locked. Due at the latest entry's time.
            AuditCopyRows.insert(c, ids, stored.getLast().occurredAt());
        }
        try (PreparedStatement s = c.prepareStatement("update audit_chain set head_seq = ?, head_hash = ?")) {
            s.setLong(1, seq);
            s.setString(2, prevHash);
            s.executeUpdate();
        }
        return ids;
    }

    /**
     * The entries with the anonymous values of every anonymized account they name ({@link AuditAnonymizer}),
     * as stored; the same list when they name none. One query for the accounts of all entries.
     */
    private static List<Values> anonymous(Connection c, List<Values> entries) throws SQLException {
        Set<Long> named = new HashSet<>();
        for (Values entry : entries) {
            named.addAll(AuditAnonymizer.accountIds(entry));
        }
        named.remove(AuditLog.SYSTEM_ACCOUNT_ID);
        if (named.isEmpty()) {
            return entries;
        }
        Map<Long, String[]> anonymized = new HashMap<>();
        // to_jsonb(a) reads the column by name: a command in the container can meet a schema from before
        // V22, which has no anonymized account.
        try (PreparedStatement s = c.prepareStatement("select a.id, a.name, a.email from account a "
                + "where a.id = any(?) and to_jsonb(a) ->> 'anonymized_at' is not null")) {
            s.setArray(1, c.createArrayOf("bigint", named.toArray(Long[]::new)));
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    anonymized.put(rs.getLong(1), new String[] {rs.getString(2), rs.getString(3)});
                }
            }
        }
        if (anonymized.isEmpty()) {
            return entries;
        }
        List<Values> out = new ArrayList<>(entries.size());
        boolean changed = false;
        for (Values entry : entries) {
            Values now = entry;
            for (Map.Entry<Long, String[]> account : anonymized.entrySet()) {
                now = AuditAnonymizer.anonymize(now, account.getKey(), account.getValue()[0], account.getValue()[1]);
            }
            changed |= now != entry;
            out.add(now);
        }
        return changed ? asStored(c, out) : entries;
    }

    /**
     * Where a recalculation stands ({@link #rechain}): the last row it passed ({@code seq}), that row's
     * hash as it was ({@code oldHash}) and as it is now ({@code newHash}), the first row whose values
     * changed ({@code fromSeq}, null while none did), and the first row that did not hold as it was
     * ({@code brokenSeq}, null while all did). Until a row changes, rows are only read; once one does not
     * hold, the walk stops there.
     */
    static final class Walk {

        long seq;
        String oldHash;
        String newHash;
        Long fromSeq;
        Long brokenSeq;

        Walk(long seq, String hash) {
            this.seq = seq;
            this.oldHash = hash;
            this.newHash = hash;
        }
    }

    /**
     * Computes the chain again from the row after {@code walk.seq} up to the row {@code to}, by
     * {@code seq}, in the caller's transaction (S10A-AC-18, ADR D-62). Each row gets the anonymous values
     * of {@code accountId} ({@link AuditAnonymizer}); once a row changed, it and every row after it get
     * the hash of the row before as {@code prevHash} and their own hash computed again. The rows are read
     * with a cursor and written a batch at a time with one {@code UPDATE} each, as V7 does, so the heap
     * holds one batch whatever the number of rows. Changed JSON goes to the database and back before its
     * hash is computed, as for appending. Only the rows it changes are locked; the head row is the
     * caller's.
     *
     * <p>Each row is checked as it was before it is computed again, as the integrity check does
     * (S10A-AC-37): its {@code seq} follows the row before, its previous hash is that row's old hash, and
     * its hash is the one computed from its stored values. At the first row that does not hold, or when
     * rows are missing at the end, the walk stops with {@code brokenSeq} set, so a break is never covered
     * by new hashes; the caller rolls the transaction back.
     *
     * @param walk where the walk stands; it ends at {@code to}, or where the chain is broken
     */
    static void rechain(Connection c, Walk walk, long to, long accountId, String name, String email)
            throws SQLException {
        try (PreparedStatement read = c.prepareStatement("select id, seq, prev_hash, hash, action, actor::text, "
                + "target::text, changes::text, occurred_at from audit_log where seq > ? and seq <= ? order by seq")) {
            read.setFetchSize(BATCH);
            read.setLong(1, walk.seq);
            read.setLong(2, to);
            List<Row> batch = new ArrayList<>(BATCH);
            try (ResultSet rs = read.executeQuery()) {
                while (rs.next()) {
                    Values stored = new Values(rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                            rs.getObject(9, OffsetDateTime.class).toInstant());
                    Values values = AuditAnonymizer.anonymize(stored, accountId, name, email);
                    batch.add(new Row(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), stored,
                            values == stored ? null : values));
                    if (batch.size() == BATCH) {
                        write(c, walk, batch);
                        if (walk.brokenSeq != null) {
                            return;
                        }
                    }
                }
            }
            if (!batch.isEmpty()) {
                write(c, walk, batch);
            }
        }
        if (walk.brokenSeq == null && walk.seq != to) {
            // Rows up to the head are committed, so the missing ones were removed behind the app's back.
            walk.brokenSeq = walk.seq;
        }
    }

    /** A row read by {@link #rechain}: as stored, and with the anonymous values when they differ. */
    private record Row(long id, long seq, String prevHash, String hash, Values stored, Values changed) {}

    /**
     * Computes one batch on from where the walk stands, writes it with one statement, and empties it.
     * Writes nothing of the batch when one of its rows does not hold.
     */
    private static void write(Connection c, Walk walk, List<Row> batch) throws SQLException {
        List<Values> changed = batch.stream().map(Row::changed).filter(Objects::nonNull).toList();
        Iterator<Values> changedAsStored = (changed.isEmpty() ? changed : asStored(c, changed)).iterator();
        List<Long> ids = new ArrayList<>(batch.size());
        List<String> prevHashes = new ArrayList<>(batch.size());
        List<String> hashes = new ArrayList<>(batch.size());
        List<String> actors = new ArrayList<>(batch.size());
        List<String> changes = new ArrayList<>(batch.size());
        List<Boolean> valuesChanged = new ArrayList<>(batch.size());
        for (Row row : batch) {
            byte[] canonical = row.seq() == walk.seq + 1 && row.prevHash().equals(walk.oldHash)
                    ? AuditChain.canonicalIfHolds(row.prevHash(), row.stored(), row.hash())
                    : null;
            if (canonical == null) {
                walk.brokenSeq = row.seq();
                batch.clear();
                return;
            }
            Values values = row.changed() == null ? row.stored() : changedAsStored.next();
            if (walk.fromSeq == null && row.changed() != null) {
                walk.fromSeq = row.seq();
            }
            walk.seq = row.seq();
            walk.oldHash = row.hash();
            if (walk.fromSeq == null) {
                walk.newHash = row.hash();
                continue;
            }
            String prevHash = walk.newHash;
            // An unchanged row keeps its canonical JSON; only its previous hash is new.
            walk.newHash = row.changed() == null ? AuditChain.hash(prevHash, canonical)
                    : AuditChain.hash(prevHash, values);
            ids.add(row.id());
            prevHashes.add(prevHash);
            hashes.add(walk.newHash);
            actors.add(values.actor());
            changes.add(values.changes());
            valuesChanged.add(row.changed() != null);
        }
        batch.clear();
        if (ids.isEmpty()) {
            return;
        }
        try (PreparedStatement s = c.prepareStatement("update audit_log a set prev_hash = u.prev_hash, hash = u.hash, "
                + "actor = case when u.changed then cast(u.actor as jsonb) else a.actor end, "
                + "changes = case when u.changed then cast(u.changes as jsonb) else a.changes end "
                + "from unnest(?::bigint[], ?::text[], ?::text[], ?::text[], ?::text[], ?::boolean[]) "
                + "as u(id, prev_hash, hash, actor, changes, changed) where a.id = u.id")) {
            s.setArray(1, c.createArrayOf("bigint", ids.toArray(Long[]::new)));
            s.setArray(2, c.createArrayOf("text", prevHashes.toArray(String[]::new)));
            s.setArray(3, c.createArrayOf("text", hashes.toArray(String[]::new)));
            s.setArray(4, c.createArrayOf("text", actors.toArray(String[]::new)));
            s.setArray(5, c.createArrayOf("text", changes.toArray(String[]::new)));
            s.setArray(6, c.createArrayOf("boolean", valuesChanged.toArray(Boolean[]::new)));
            s.executeUpdate();
        }
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
