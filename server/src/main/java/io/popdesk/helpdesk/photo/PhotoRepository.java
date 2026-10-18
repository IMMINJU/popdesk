package io.popdesk.helpdesk.photo;

import io.popdesk.helpdesk.Viewer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Profile photos (table {@code account_photo}): one stored PNG per account. The read takes the
 * {@link Viewer} and applies who may see whose photo in the query, so a photo outside the scope is the
 * same as no photo and as no account (S1A-AC-25 to S1A-AC-27).
 */
@Repository
public class PhotoRepository {

    /**
     * Whose photo the viewer gets, on the photo's account {@code t}, with six parameters (role, account
     * ID, three times over): admins every photo; everyone their own; agents those of staff and of
     * customers of a company they are assigned to; customers those of customers who share a company
     * with them, and never one of staff (ANON-1). Memberships are read on every call (S0-AC-26).
     */
    private static final String SCOPE = "(? = 'ADMIN' or t.id = ? "
            + "or (? = 'AGENT' and (t.role <> 'CUSTOMER' or exists (select 1 from company_member m "
            + "join company_agent ca on ca.company_id = m.company_id "
            + "where m.account_id = t.id and ca.account_id = ?))) "
            + "or (? = 'CUSTOMER' and t.role = 'CUSTOMER' and exists (select 1 from company_member m "
            + "join company_member mine on mine.company_id = m.company_id "
            + "where m.account_id = t.id and mine.account_id = ?)))";

    private final JdbcClient jdbc;

    PhotoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The stored PNG of the account, if it has a photo and the viewer may see it. */
    public Optional<byte[]> find(Viewer viewer, long accountId) {
        String role = viewer.role().name();
        return jdbc.sql("select p.png from account_photo p join account t on t.id = p.account_id "
                        + "where p.account_id = ? and " + SCOPE)
                .params(accountId, role, viewer.accountId(), role, viewer.accountId(), role, viewer.accountId())
                .query(byte[].class).optional();
    }

    /** Stores the account's photo in place of the one it had. */
    void save(long accountId, byte[] png, Instant now) {
        jdbc.sql("insert into account_photo (account_id, tenant_id, png, size_bytes, updated_at) values (?, 1, ?, ?, ?) "
                        + "on conflict (account_id) do update set png = excluded.png, "
                        + "size_bytes = excluded.size_bytes, updated_at = excluded.updated_at")
                .params(accountId, png, png.length, Timestamp.from(now)).update();
    }

    /** Removes the account's photo; also for an anonymization, in its transaction (S10A-AC-09). */
    public void delete(long accountId) {
        jdbc.sql("delete from account_photo where account_id = ?").param(accountId).update();
    }
}
