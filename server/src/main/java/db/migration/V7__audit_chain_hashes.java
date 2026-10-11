package db.migration;

import io.popdesk.helpdesk.audit.AuditChain;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Chains the audit rows that are there (S1B-AC-12): in the order of time and then ID each gets its
 * {@code seq} from 1, the hash of the row before, and its own hash, computed by the function new
 * entries use ({@link AuditChain#hash}). Then the columns V6 added become {@code NOT NULL} and unique,
 * and the chain's head is the last row.
 *
 * <p>Flyway creates this class itself, so it uses static functions only, and runs it in one
 * transaction: if the app dies here nothing is kept and the next start does it all again (S1B-AC-13).
 * The rows are read with a cursor and written a batch at a time with one statement, so the heap stays
 * small whatever the number of rows.
 */
public class V7__audit_chain_hashes extends BaseJavaMigration {

    private static final int BATCH = 2000;

    @Override
    public void migrate(Context context) throws SQLException {
        Connection c = context.getConnection();
        long seq = 0;
        String hash = AuditChain.ZERO_HASH;
        try (PreparedStatement read = c.prepareStatement("select id, action, actor::text, target::text, "
                + "changes::text, occurred_at from audit_log order by occurred_at, id");
                PreparedStatement write = c.prepareStatement("update audit_log a set seq = u.seq, "
                        + "prev_hash = u.prev_hash, hash = u.hash from unnest(?::bigint[], ?::bigint[], ?::text[], "
                        + "?::text[]) as u(id, seq, prev_hash, hash) where a.id = u.id")) {
            read.setFetchSize(BATCH);
            List<Long> ids = new ArrayList<>(BATCH);
            List<Long> seqs = new ArrayList<>(BATCH);
            List<String> prevHashes = new ArrayList<>(BATCH);
            List<String> hashes = new ArrayList<>(BATCH);
            try (ResultSet rs = read.executeQuery()) {
                while (rs.next()) {
                    String prevHash = hash;
                    hash = AuditChain.hash(prevHash, new AuditChain.Values(rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getObject(6, OffsetDateTime.class).toInstant()));
                    ids.add(rs.getLong(1));
                    seqs.add(++seq);
                    prevHashes.add(prevHash);
                    hashes.add(hash);
                    if (ids.size() == BATCH) {
                        write(c, write, ids, seqs, prevHashes, hashes);
                    }
                }
            }
            if (!ids.isEmpty()) {
                write(c, write, ids, seqs, prevHashes, hashes);
            }
        }
        try (PreparedStatement head = c.prepareStatement("update audit_chain set head_seq = ?, head_hash = ?")) {
            head.setLong(1, seq);
            head.setString(2, hash);
            head.executeUpdate();
        }
        try (Statement s = c.createStatement()) {
            // prev_hash unique: two rows can never follow the same row, whatever the app does (AUDIT-6).
            s.execute("alter table audit_log alter column seq set not null, alter column prev_hash set not null, "
                    + "alter column hash set not null, add constraint audit_log_seq_key unique (seq), "
                    + "add constraint audit_log_prev_hash_key unique (prev_hash)");
        }
    }

    private static void write(Connection c, PreparedStatement write, List<Long> ids, List<Long> seqs,
            List<String> prevHashes, List<String> hashes) throws SQLException {
        write.setArray(1, c.createArrayOf("bigint", ids.toArray(Long[]::new)));
        write.setArray(2, c.createArrayOf("bigint", seqs.toArray(Long[]::new)));
        write.setArray(3, c.createArrayOf("text", prevHashes.toArray(String[]::new)));
        write.setArray(4, c.createArrayOf("text", hashes.toArray(String[]::new)));
        write.executeUpdate();
        ids.clear();
        seqs.clear();
        prevHashes.clear();
        hashes.clear();
    }
}
