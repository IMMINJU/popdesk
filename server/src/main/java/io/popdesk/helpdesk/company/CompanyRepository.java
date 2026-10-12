package io.popdesk.helpdesk.company;

import io.popdesk.helpdesk.AccountStatus;
import io.popdesk.helpdesk.CompanyStatus;
import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.account.AccountRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

    /**
     * A customer or agent of a company; {@code photoUpdatedAt} is null without a profile photo.
     * {@code manager}: the customer is a manager of this company; always false for an agent.
     */
    public record MemberRow(long companyId, long accountId, String name, String email, AccountStatus status,
            Instant photoUpdatedAt, boolean manager) {}

    /** An admin or agent who can be the assignee of an issue; {@code photoUpdatedAt} is null without a profile photo. */
    public record StaffRow(long accountId, String name, Role role, Instant photoUpdatedAt) {}

    /** A company the caller belongs to as a customer, and whether they are a manager of it. */
    record OwnMembership(CompanyRow company, boolean manager) {}

    /** A membership as a row of the member list names it. */
    record MemberRef(long accountId, boolean manager) {}

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

    /**
     * Who can be the assignee of an issue (ISSUE-7), as SQL on the account {@code a} and the company
     * {@code c}: an {@code ACTIVE} admin, or an {@code ACTIVE} agent assigned to that company. An
     * assignment to another company does not count.
     */
    private static final String ASSIGNABLE = "a.status = 'ACTIVE' and (a.role = 'ADMIN' or (a.role = 'AGENT' and "
            + "exists (select 1 from company_agent aa where aa.company_id = c.id and aa.account_id = a.id)))";

    /** Who staff can file an issue for (ISSUE-9), as SQL on the member's account {@code a}. */
    private static final String REPORTER = "a.role = 'CUSTOMER' and a.status = 'ACTIVE'";

    /**
     * The reporters of one company with the search text, with the parameters of {@link #reporters}. The
     * search is one condition in brackets, so the company and the status hold for a name and for an email
     * alike. The text is a LIKE pattern in which {@code %} and {@code _} find only themselves (COM-5).
     */
    private static final String REPORTERS = "m.company_id = ? and " + REPORTER + " and " + SCOPE
            + " and (cast(? as text) is null or a.name ilike ? escape '\\' or a.email ilike ? escape '\\')";

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

    /**
     * The company with this code, if the viewer may see it, locked until the end of the transaction. A
     * change of its names or status takes this lock before it reads them.
     */
    Optional<CompanyRow> lockByCode(Viewer viewer, String code) {
        return jdbc.sql("select " + COLUMNS + " from company c where c.code = ? and " + SCOPE
                        + " for no key update of c")
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
        return jdbc.sql("select m.company_id, a.id, a.name, a.email, a.status, p.updated_at, "
                        + (role == Role.CUSTOMER ? "m.manager" : "false") + " from "
                        + memberTable(role) + " m join account a on a.id = m.account_id "
                        + "join company c on c.id = m.company_id left join account_photo p on p.account_id = a.id "
                        + "where m.company_id = ? and " + SCOPE + " order by a.name, a.id offset ? limit ?")
                .params(with(with(new Object[] {companyId}, scope(viewer)), offset, limit))
                .query(CompanyRepository::member).list();
    }

    public long countMembers(Viewer viewer, long companyId, Role role) {
        return jdbc.sql("select count(*) from " + memberTable(role) + " m join company c on c.id = m.company_id "
                        + "where m.company_id = ? and " + SCOPE)
                .params(with(new Object[] {companyId}, scope(viewer))).query(Long.class).single();
    }

    /**
     * Everyone who can be the assignee of an issue of this company, in name order, if the viewer may see
     * the company (ISSUE-7, S3-AC-27): {@link #ASSIGNABLE}.
     */
    public List<StaffRow> listAssignable(Viewer viewer, long companyId) {
        return jdbc.sql("select a.id, a.name, a.role, p.updated_at from account a "
                        + "join company c on c.id = ? left join account_photo p on p.account_id = a.id "
                        + "where " + ASSIGNABLE + " and " + SCOPE + " order by a.name, a.id")
                .params(with(new Object[] {companyId}, scope(viewer)))
                .query(CompanyRepository::staff).list();
    }

    /**
     * The account, if it can be the assignee of an issue of this company and the viewer may see the
     * company (S3-AC-25). Empty for every other account, whatever the reason.
     */
    public Optional<StaffRow> findAssignable(Viewer viewer, long companyId, long accountId) {
        return jdbc.sql("select a.id, a.name, a.role, p.updated_at from account a "
                        + "join company c on c.id = ? left join account_photo p on p.account_id = a.id "
                        + "where a.id = ? and " + ASSIGNABLE + " and " + SCOPE)
                .params(with(new Object[] {companyId, accountId}, scope(viewer)))
                .query(CompanyRepository::staff).optional();
    }

    /**
     * Whether the account is an {@code ACTIVE} customer who belongs to this company, which the viewer may
     * see: who staff can file an issue for (ISSUE-9, S3-AC-33).
     */
    public boolean isReporter(Viewer viewer, long companyId, long accountId) {
        return jdbc.sql("select count(*) from company_member m join account a on a.id = m.account_id "
                        + "join company c on c.id = m.company_id where m.company_id = ? and a.id = ? and " + REPORTER
                        + " and " + SCOPE)
                .params(with(new Object[] {companyId, accountId}, scope(viewer)))
                .query(Long.class).single() > 0;
    }

    /**
     * One page of the customers staff can file an issue for in this company, in name order, if the
     * viewer may see the company (S3-AC-40). {@code q} narrows them to those whose name or email holds
     * it, ignoring case; null leaves that out.
     */
    public List<MemberRow> listReporters(Viewer viewer, long companyId, String q, long offset, int limit) {
        return jdbc.sql("select m.company_id, a.id, a.name, a.email, a.status, p.updated_at, m.manager "
                        + "from company_member m join account a on a.id = m.account_id "
                        + "join company c on c.id = m.company_id left join account_photo p on p.account_id = a.id "
                        + "where " + REPORTERS + " order by a.name, a.id offset ? limit ?")
                .params(with(reporters(viewer, companyId, q), offset, limit))
                .query(CompanyRepository::member).list();
    }

    public long countReporters(Viewer viewer, long companyId, String q) {
        return jdbc.sql("select count(*) from company_member m join account a on a.id = m.account_id "
                        + "join company c on c.id = m.company_id where " + REPORTERS)
                .params(reporters(viewer, companyId, q)).query(Long.class).single();
    }

    private static Object[] reporters(Viewer viewer, long companyId, String q) {
        String pattern = q == null ? null : AccountRepository.containing(q);
        return with(with(new Object[] {companyId}, scope(viewer)), pattern, pattern, pattern);
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

    /** The codes of the companies where the viewer, a customer, is a manager (S2-AC-07). */
    public List<String> listManagedCodes(Viewer viewer) {
        return jdbc.sql("select c.code from company_member cm join company c on c.id = cm.company_id "
                        + "where cm.account_id = ? and cm.manager and 'CUSTOMER' = ? order by c.code")
                .params(viewer.accountId(), viewer.role().name()).query(String.class).list();
    }

    /**
     * The company with this code and the viewer's own membership of it, in one statement; empty when
     * the viewer is no customer who belongs to it. With {@code lock}, the membership row is locked
     * {@code FOR SHARE} until the end of the transaction: a change of the mark or the end of the
     * membership waits for the transaction, or the transaction reads what that change left (S2-AC-11).
     */
    Optional<OwnMembership> findOwnMembership(Viewer viewer, String code, boolean lock) {
        return jdbc.sql("select " + COLUMNS + ", cm.manager from company c join company_member cm "
                        + "on cm.company_id = c.id where c.code = ? and cm.account_id = ? and 'CUSTOMER' = ?"
                        + (lock ? " for share of cm" : ""))
                .params(code, viewer.accountId(), viewer.role().name())
                .query((rs, n) -> new OwnMembership(company(rs, n), rs.getBoolean("manager"))).optional();
    }

    /** The membership of this company that the member list names with this row ID. */
    Optional<MemberRef> findMemberByRowId(long companyId, UUID rowId) {
        return jdbc.sql("select account_id, manager from company_member where company_id = ? and row_id = ?")
                .params(companyId, rowId)
                .query((rs, n) -> new MemberRef(rs.getLong("account_id"), rs.getBoolean("manager"))).optional();
    }

    /** Whether the account belongs to the company as a customer. */
    boolean isMember(long companyId, long accountId) {
        return jdbc.sql("select exists (select 1 from company_member where company_id = ? and account_id = ?)")
                .params(companyId, accountId).query(Boolean.class).single();
    }

    /** How many companies the account belongs to as a customer. */
    long countMemberships(long accountId) {
        return jdbc.sql("select count(*) from company_member where account_id = ?").param(accountId)
                .query(Long.class).single();
    }

    // Writes

    /** A new {@code ACTIVE} company; empty when another company has the code (409). */
    Optional<Long> insert(String code, String name, String legalName, Instant now) {
        return jdbc.sql("insert into company (tenant_id, code, name, legal_name, status, created_at) "
                        + "values (1, ?, ?, ?, ?, ?) on conflict (code) do nothing returning id")
                .params(code, name, legalName, CompanyStatus.ACTIVE.name(), Timestamp.from(now))
                .query(Long.class).optional();
    }

    void updateNames(long id, String name, String legalName) {
        jdbc.sql("update company set name = ?, legal_name = ? where id = ?").params(name, legalName, id).update();
    }

    void updateStatus(long id, CompanyStatus status) {
        jdbc.sql("update company set status = ? where id = ?").params(status.name(), id).update();
    }

    /** Makes a customer belong to a company; false when the customer belonged to it already. */
    public boolean insertMember(long companyId, long accountId, Instant now) {
        return jdbc.sql("insert into company_member (company_id, account_id, tenant_id, created_at) values (?, ?, 1, ?) "
                        + "on conflict do nothing")
                .params(companyId, accountId, Timestamp.from(now)).update() == 1;
    }

    /**
     * Makes a customer belong to a company with the row ID of the membership request that asked for it
     * (S2-AC-34); false when the customer belonged to it already.
     */
    boolean insertMember(long companyId, long accountId, UUID rowId, Instant now) {
        return jdbc.sql("insert into company_member (company_id, account_id, tenant_id, created_at, row_id) "
                        + "values (?, ?, 1, ?, ?) on conflict (company_id, account_id) do nothing")
                .params(companyId, accountId, Timestamp.from(now), rowId).update() == 1;
    }

    /** The manager mark of a membership, locked until the end of the transaction; empty for no member. */
    Optional<Boolean> lockManager(long companyId, long accountId) {
        return jdbc.sql("select manager from company_member where company_id = ? and account_id = ? "
                        + "for no key update")
                .params(companyId, accountId).query(Boolean.class).optional();
    }

    void updateManager(long companyId, long accountId, boolean manager) {
        jdbc.sql("update company_member set manager = ? where company_id = ? and account_id = ?")
                .params(manager, companyId, accountId).update();
    }

    /**
     * Takes a customer who is no manager out of a company; false when the customer did not belong to it
     * or is a manager now.
     */
    boolean deleteMemberUnlessManager(long companyId, long accountId) {
        return jdbc.sql("delete from company_member where company_id = ? and account_id = ? and not manager")
                .params(companyId, accountId).update() == 1;
    }

    /** Takes a customer out of a company; false when the customer did not belong to it. */
    boolean deleteMember(long companyId, long accountId) {
        return jdbc.sql("delete from company_member where company_id = ? and account_id = ?")
                .params(companyId, accountId).update() == 1;
    }

    /** Takes an agent off a company; false when the agent was not assigned to it. */
    boolean deleteAgent(long companyId, long accountId) {
        return jdbc.sql("delete from company_agent where company_id = ? and account_id = ?")
                .params(companyId, accountId).update() == 1;
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

    private static StaffRow staff(ResultSet rs, int row) throws SQLException {
        Timestamp photo = rs.getTimestamp(4);
        return new StaffRow(rs.getLong(1), rs.getString(2), Role.valueOf(rs.getString(3)),
                photo == null ? null : photo.toInstant());
    }

    private static MemberRow member(ResultSet rs, int row) throws SQLException {
        Timestamp photo = rs.getTimestamp(6);
        return new MemberRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                AccountStatus.valueOf(rs.getString(5)), photo == null ? null : photo.toInstant(),
                rs.getBoolean(7));
    }
}
