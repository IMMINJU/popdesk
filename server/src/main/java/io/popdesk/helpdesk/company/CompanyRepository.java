package io.popdesk.helpdesk.company;

import io.popdesk.helpdesk.AccountStatus;
import io.popdesk.helpdesk.CompanyStatus;
import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.Viewer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Customer companies and who belongs to them (tables {@code company}, {@code company_member},
 * {@code company_agent}). Every read takes the {@link Viewer} and applies its scope in the query
 * (S0-AC-84, PROJ-5): admins see every company, agents the ones they are assigned to, customers the
 * ones they belong to. Memberships and assignments are read from the tables on every call, never kept
 * in the session (S0-AC-26).
 */
@Repository
public class CompanyRepository {

    /** One company row. */
    public record CompanyRow(long id, String code, String name, String legalName, CompanyStatus status,
            Instant createdAt) {}

    /** A customer or agent of a company. */
    public record MemberRow(long companyId, long accountId, String name, String email, AccountStatus status) {}

    /** A company someone belongs to, by account. */
    public record MembershipRow(long accountId, String code, String name) {}

    private static final String COLUMNS = "c.id, c.code, c.name, c.legal_name, c.status, c.created_at";

    /**
     * The scope as SQL on {@code c}, with two parameters: the viewer's role and account ID. Admins see
     * everything; agents and customers only the rows that tie them to the company.
     */
    private static final String SCOPE = "(? = 'ADMIN' or exists (select 1 from company_agent ca where "
            + "ca.company_id = c.id and ca.account_id = ? and 'AGENT' = ?) or exists (select 1 from company_member cm "
            + "where cm.company_id = c.id and cm.account_id = ? and 'CUSTOMER' = ?))";

    private final JdbcClient jdbc;

    CompanyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Object[] scope(Viewer viewer) {
        String role = viewer.role().name();
        return new Object[] {role, viewer.accountId(), role, viewer.accountId(), role};
    }

    private static Object[] with(Object[] first, Object... more) {
        Object[] out = new Object[first.length + more.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(more, 0, out, first.length, more.length);
        return out;
    }

    /** The company with this code, if the viewer may see it. */
    public Optional<CompanyRow> findByCode(Viewer viewer, String code) {
        return jdbc.sql("select " + COLUMNS + " from company c where c.code = ? and " + SCOPE)
                .params(with(new Object[] {code}, scope(viewer)))
                .query(CompanyRepository::company).optional();
    }

    /** The companies with these codes that the viewer may see. */
    public List<CompanyRow> findByCodes(Viewer viewer, Collection<String> codes) {
        if (codes.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select " + COLUMNS + " from company c where c.code = any(?) and " + SCOPE)
                .params(with(new Object[] {codes.toArray(String[]::new)}, scope(viewer)))
                .query(CompanyRepository::company).list();
    }

    /** One page of the companies the viewer may see, in code order. */
    public List<CompanyRow> list(Viewer viewer, long offset, int limit) {
        return jdbc.sql("select " + COLUMNS + " from company c where " + SCOPE + " order by c.code offset ? limit ?")
                .params(with(scope(viewer), offset, limit))
                .query(CompanyRepository::company).list();
    }

    /**
     * The companies the viewer is tied to, whatever their status, in code order: the ones a customer
     * belongs to or an agent is assigned to. None for an admin, who is tied to no company.
     */
    public List<CompanyRow> listOwn(Viewer viewer) {
        String role = viewer.role().name();
        return jdbc.sql("select " + COLUMNS + " from company c where exists (select 1 from company_agent ca where "
                        + "ca.company_id = c.id and ca.account_id = ? and 'AGENT' = ?) or exists (select 1 from "
                        + "company_member cm where cm.company_id = c.id and cm.account_id = ? and 'CUSTOMER' = ?) "
                        + "order by c.code")
                .params(viewer.accountId(), role, viewer.accountId(), role)
                .query(CompanyRepository::company).list();
    }

    public long count(Viewer viewer) {
        return jdbc.sql("select count(*) from company c where " + SCOPE)
                .params(scope(viewer)).query(Long.class).single();
    }

    /** One page of a company's customers or agents, in name order, if the viewer may see the company. */
    public List<MemberRow> listMembers(Viewer viewer, long companyId, Role role, long offset, int limit) {
        return jdbc.sql("select m.company_id, a.id, a.name, a.email, a.status from " + memberTable(role) + " m "
                        + "join account a on a.id = m.account_id join company c on c.id = m.company_id "
                        + "where m.company_id = ? and " + SCOPE + " order by a.name, a.id offset ? limit ?")
                .params(with(with(new Object[] {companyId}, scope(viewer)), offset, limit))
                .query(CompanyRepository::member).list();
    }

    public long countMembers(Viewer viewer, long companyId, Role role) {
        return jdbc.sql("select count(*) from " + memberTable(role) + " m join company c on c.id = m.company_id "
                        + "where m.company_id = ? and " + SCOPE)
                .params(with(new Object[] {companyId}, scope(viewer))).query(Long.class).single();
    }

    /** The companies that these customers belong to, among those the viewer may see. */
    public List<MembershipRow> listMemberships(Viewer viewer, Collection<Long> accountIds) {
        if (accountIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select m.account_id, c.code, c.name from company_member m join company c on c.id = m.company_id "
                        + "where m.account_id = any(?) and " + SCOPE + " order by c.code")
                .params(with(new Object[] {accountIds.toArray(Long[]::new)}, scope(viewer)))
                .query((rs, n) -> new MembershipRow(rs.getLong(1), rs.getString(2), rs.getString(3))).list();
    }

    // Writes

    /** A new {@code ACTIVE} company; empty when another company has the code (409). */
    Optional<Long> insert(String code, String name, String legalName, Instant now) {
        return jdbc.sql("insert into company (tenant_id, code, name, legal_name, status, created_at) "
                        + "values (1, ?, ?, ?, ?, ?) on conflict (code) do nothing returning id")
                .params(code, name, legalName, CompanyStatus.ACTIVE.name(), Timestamp.from(now))
                .query(Long.class).optional();
    }

    /** Makes a customer belong to a company. */
    public void insertMember(long companyId, long accountId, Instant now) {
        jdbc.sql("insert into company_member (company_id, account_id, tenant_id, created_at) values (?, ?, 1, ?) "
                        + "on conflict do nothing")
                .params(companyId, accountId, Timestamp.from(now)).update();
    }

    /** Assigns an agent to a company; false when the agent was assigned already. */
    boolean insertAgent(long companyId, long accountId, Instant now) {
        return jdbc.sql("insert into company_agent (company_id, account_id, tenant_id, created_at) values (?, ?, 1, ?) "
                        + "on conflict do nothing")
                .params(companyId, accountId, Timestamp.from(now)).update() == 1;
    }

    /**
     * Raises the company's issue counter and returns the new number (ISSUE-3, S0-AC-44). The row stays
     * locked until the end of the transaction, so the filing calls this last (architecture.md
     * "Concurrency and lock order", step 4).
     */
    public int updateNextIssueNumber(long companyId) {
        return jdbc.sql("update company set last_issue_number = last_issue_number + 1 where id = ? "
                        + "returning last_issue_number")
                .param(companyId).query(Integer.class).single();
    }

    private static String memberTable(Role role) {
        return switch (role) {
            case CUSTOMER -> "company_member";
            case AGENT -> "company_agent";
            case ADMIN -> throw new IllegalArgumentException("Admins do not belong to companies");
        };
    }

    private static CompanyRow company(ResultSet rs, int row) throws SQLException {
        return new CompanyRow(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("legal_name"),
                CompanyStatus.valueOf(rs.getString("status")), rs.getTimestamp("created_at").toInstant());
    }

    private static MemberRow member(ResultSet rs, int row) throws SQLException {
        return new MemberRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                AccountStatus.valueOf(rs.getString(5)));
    }
}
