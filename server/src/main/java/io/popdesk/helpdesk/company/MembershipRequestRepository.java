package io.popdesk.helpdesk.company;

import io.popdesk.helpdesk.AccountStatus;
import io.popdesk.helpdesk.Role;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Membership requests (table {@code membership_request}) and the member list a manager reads, which
 * holds memberships and open requests as one sequence (S2-AC-08, S2-AC-16).
 *
 * <p>Nothing here takes a {@code Viewer}: the services of this package call it after they checked the
 * caller, a manager of the company ({@link MemberService}) or an admin
 * ({@link MembershipRequestService}), and every read by row ID names the company (S2-AC-32).
 */
@Repository
class MembershipRequestRepository {

    /** How a request ended, or that it is open. */
    enum State {
        PENDING,
        APPROVED,
        REJECTED,
        WITHDRAWN
    }

    /** One request row, with the code of its company. {@code email} is what the manager typed. */
    record RequestRow(long id, UUID rowId, long companyId, String companyCode, long accountId, String email,
            State state) {}

    private static final String REQUEST_COLUMNS = "r.id, r.row_id, r.company_id, c.code, r.account_id, r.email, "
            + "r.state";

    /**
     * Memberships and open requests of one company with the same columns, so that one ORDER BY and one
     * OFFSET page both kinds together. Two parameters: the company ID, twice.
     */
    private static final String LISTED = "(select cm.row_id, a.name, a.email, a.status, cm.manager "
            + "from company_member cm join account a on a.id = cm.account_id where cm.company_id = ? "
            + "union all select r.row_id, r.name, r.email, 'INVITED', false from membership_request r "
            + "where r.company_id = ? and r.state = 'PENDING') m";

    private static final String PENDING_FROM = "from membership_request r join company c on c.id = r.company_id "
            + "join account a on a.id = r.account_id join account b on b.id = r.requested_by "
            + "left join invitation i on i.account_id = a.id "
            + "where r.state = 'PENDING' and (cast(? as bigint) is null or r.company_id = ?)";

    private final JdbcClient jdbc;

    MembershipRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // The member list

    /**
     * One page of the member list of a company, in email order: a membership with the account's name,
     * email, and status, or an open request with what the manager typed and {@code INVITED}.
     */
    List<MemberService.Row> listRows(long companyId, long offset, int limit) {
        return jdbc.sql("select m.row_id, m.name, m.email, m.status, m.manager from " + LISTED
                        + " order by lower(m.email), m.row_id offset ? limit ?")
                .params(companyId, companyId, offset, limit)
                .query((rs, n) -> new MemberService.Row(rs.getObject("row_id", UUID.class), rs.getString("name"),
                        rs.getString("email"), AccountStatus.valueOf(rs.getString("status")),
                        rs.getBoolean("manager")))
                .list();
    }

    long countRows(long companyId) {
        return jdbc.sql("select count(*) from " + LISTED).params(companyId, companyId).query(Long.class).single();
    }

    /**
     * The rows of the member list that say {@code INVITED}: customers of the company who have not
     * accepted, and open requests (S2-AC-21).
     */
    long countInvited(long companyId) {
        return jdbc.sql("select count(*) from " + LISTED + " where m.status = 'INVITED'")
                .params(companyId, companyId).query(Long.class).single();
    }

    // Requests

    /** The open request of this company that the member list names with this row ID. */
    Optional<RequestRow> findPendingByRowId(long companyId, UUID rowId) {
        return jdbc.sql("select " + REQUEST_COLUMNS + " from membership_request r join company c "
                        + "on c.id = r.company_id where r.company_id = ? and r.row_id = ? and r.state = 'PENDING'")
                .params(companyId, rowId).query(MembershipRequestRepository::request).optional();
    }

    /** The request, open or closed, locked until the end of the transaction. */
    Optional<RequestRow> lockById(long id) {
        return jdbc.sql("select " + REQUEST_COLUMNS + " from membership_request r join company c "
                        + "on c.id = r.company_id where r.id = ? for no key update of r")
                .param(id).query(MembershipRequestRepository::request).optional();
    }

    /** The open request for this account in this company, locked until the end of the transaction. */
    Optional<RequestRow> lockPending(long companyId, long accountId) {
        return jdbc.sql("select " + REQUEST_COLUMNS + " from membership_request r join company c "
                        + "on c.id = r.company_id where r.company_id = ? and r.account_id = ? "
                        + "and r.state = 'PENDING' for no key update of r")
                .params(companyId, accountId).query(MembershipRequestRepository::request).optional();
    }

    /**
     * A new open request; empty when the company already has an open one for the account, also one
     * another transaction is making now.
     */
    Optional<Long> insert(long companyId, long accountId, long requestedBy, String email, String name, Instant now) {
        return jdbc.sql("insert into membership_request (tenant_id, company_id, account_id, requested_by, email, "
                        + "name, state, created_at) values (1, ?, ?, ?, ?, ?, 'PENDING', ?) "
                        + "on conflict (company_id, account_id) where state = 'PENDING' do nothing returning id")
                .params(companyId, accountId, requestedBy, email, name, Timestamp.from(now))
                .query(Long.class).optional();
    }

    /** Closes an open request; false when it was closed already. */
    boolean updateClosed(long id, State state, long closedBy, Instant now) {
        return jdbc.sql("update membership_request set state = ?, closed_at = ?, closed_by = ? "
                        + "where id = ? and state = 'PENDING'")
                .params(state.name(), Timestamp.from(now), closedBy, id).update() == 1;
    }

    // The admin's list

    /**
     * One page of the open requests, oldest first, each with its account as it is now; of one company
     * when {@code companyId} is not null.
     */
    List<MembershipRequestService.Pending> listPending(Long companyId, long offset, int limit) {
        return jdbc.sql("select r.id, c.code, c.name as company_name, b.id as requester_id, "
                        + "b.name as requester_name, b.email as requester_email, r.name, r.email, a.id as account_id, "
                        + "a.name as account_name, a.role, a.status, i.cancelled_at is not null as cancelled, "
                        + "r.created_at " + PENDING_FROM + " order by r.created_at, r.id offset ? limit ?")
                .params(companyId, companyId, offset, limit)
                .query((rs, n) -> new MembershipRequestService.Pending(rs.getLong("id"), rs.getString("code"),
                        rs.getString("company_name"), rs.getLong("requester_id"), rs.getString("requester_name"),
                        rs.getString("requester_email"), rs.getString("name"), rs.getString("email"),
                        rs.getLong("account_id"), rs.getString("account_name"), Role.valueOf(rs.getString("role")),
                        AccountStatus.valueOf(rs.getString("status")), rs.getBoolean("cancelled"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    long countPending(Long companyId) {
        return jdbc.sql("select count(*) " + PENDING_FROM).params(companyId, companyId).query(Long.class).single();
    }

    /** How many open requests each of these companies has; a company without one is not in the map. */
    Map<Long, Integer> countPendingByCompany(Collection<Long> companyIds) {
        Map<Long, Integer> counts = new HashMap<>();
        if (companyIds.isEmpty()) {
            return counts;
        }
        jdbc.sql("select company_id, count(*) from membership_request where state = 'PENDING' "
                        + "and company_id = any(?) group by company_id")
                .param(companyIds.toArray(Long[]::new))
                .query(rs -> {
                    counts.put(rs.getLong(1), rs.getInt(2));
                });
        return counts;
    }

    private static RequestRow request(ResultSet rs, int row) throws SQLException {
        return new RequestRow(rs.getLong("id"), rs.getObject("row_id", UUID.class), rs.getLong("company_id"),
                rs.getString("code"), rs.getLong("account_id"), rs.getString("email"),
                State.valueOf(rs.getString("state")));
    }
}
