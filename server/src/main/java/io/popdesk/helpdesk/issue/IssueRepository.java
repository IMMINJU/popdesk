package io.popdesk.helpdesk.issue;

import static io.popdesk.helpdesk.issue.IssueScope.concat;

import io.popdesk.helpdesk.CompanyStatus;
import io.popdesk.helpdesk.IssueStatus;
import io.popdesk.helpdesk.Priority;
import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.account.AccountRepository;
import io.popdesk.helpdesk.sla.IssueClocks;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Issues (table {@code issue}). Every read takes the {@link Viewer} and applies {@link IssueScope} in
 * the query (S0-AC-84); an issue outside the scope is the same as one that does not exist (COM-1).
 */
@Repository
public class IssueRepository {

    /** An account an issue names besides its reporter; {@code photoUpdatedAt} is null without a profile photo. */
    public record Staff(long accountId, String name, Role role, Instant photoUpdatedAt) {}

    /**
     * One issue with its company and reporter; {@code reporterPhotoUpdatedAt} is null without a profile photo.
     * {@code createdBy} is the staff member who filed it for the reporter, null when the reporter filed it
     * (ISSUE-9). {@code assignee} is null when nobody is assigned; {@code assigneeUnavailable} says that
     * the assignee can no longer see the issue ({@link #ASSIGNEE_UNAVAILABLE}). {@code dueDate} is null
     * without a due date (DUE-1). {@code sla} is null when the issue is not under SLA (S8A-AC-11).
     */
    public record IssueRow(UUID id, long companyId, String companyCode, String companyName, int number, String title,
            String content, IssueStatus status, Priority priority, boolean privateIssue, long reporterId,
            String reporterName, Role reporterRole, Instant reporterPhotoUpdatedAt, Staff createdBy, String channel,
            Staff assignee, boolean assigneeUnavailable, Instant firstResponseAt,
            Instant resolvedAt, Instant closedAt, long version, Instant createdAt, Instant updatedAt,
            LocalDate dueDate, IssueClocks sla) {

        public String key() {
            return companyCode + "-" + number;
        }

        /**
         * Whether the issue can be reopened at {@code now} (STATUS-4): it is {@code RESOLVED}, or it was
         * closed less than {@link #REOPEN_WINDOW} ago. It says nothing about who asks.
         */
        public boolean reopenable(Instant now) {
            return status == IssueStatus.RESOLVED
                    || status == IssueStatus.CLOSED && closedAt != null && closedAt.isAfter(now.minus(REOPEN_WINDOW));
        }
    }

    /** How long after it was closed an issue can still be reopened (STATUS-4). */
    public static final Duration REOPEN_WINDOW = Duration.ofDays(14);

    /**
     * "The assignee can no longer see the issue" (ISSUE-11), as SQL on the issue alias {@code i}, without
     * parameters: the issue has an assignee whose account is not {@code ACTIVE}, or who is an agent
     * without an assignment to the issue's company; an assignment to another company does not count. An
     * admin sees every issue. The list, the detail, and the list filter use this one condition.
     */
    public static final String ASSIGNEE_UNAVAILABLE = "exists (select 1 from account ua where ua.id = i.assignee_id "
            + "and (ua.status <> 'ACTIVE' or (ua.role = 'AGENT' and not exists (select 1 from company_agent uca "
            + "where uca.company_id = i.company_id and uca.account_id = ua.id))))";

    /** Open issues an account is the assignee of (ACCT-7), as SQL on {@code i} with one parameter, the account. */
    private static final String OPEN_ASSIGNMENT = "i.assignee_id = ? and i.status <> 'CLOSED'";

    private static final String COLUMNS = "i.id, i.company_id, c.code, c.name, i.number, i.title, i.content, "
            + "i.status, i.priority, i.is_private, i.reporter_id, r.name as reporter_name, r.role as reporter_role, "
            + "rp.updated_at as reporter_photo_updated_at, i.channel, i.first_response_at, i.resolved_at, i.closed_at, "
            + "i.version, i.created_at, i.updated_at, i.due_date, "
            + "i.created_by, cb.name as created_by_name, cb.role as created_by_role, "
            + "cbp.updated_at as created_by_photo_updated_at, "
            + "i.assignee_id, asg.name as assignee_name, asg.role as assignee_role, "
            + "asgp.updated_at as assignee_photo_updated_at, " + ASSIGNEE_UNAVAILABLE + " as assignee_unavailable, "
            + IssueClocks.COLUMNS;

    /** The joins of {@link #COLUMNS} to the issue {@code i}. */
    private static final String JOINS = "join company c on c.id = i.company_id "
            + "join account r on r.id = i.reporter_id "
            + "left join account_photo rp on rp.account_id = i.reporter_id "
            + "left join account cb on cb.id = i.created_by "
            + "left join account_photo cbp on cbp.account_id = i.created_by "
            + "left join account asg on asg.id = i.assignee_id "
            + "left join account_photo asgp on asgp.account_id = i.assignee_id ";

    private static final String SELECT = "select " + COLUMNS + " from issue i " + JOINS;

    /**
     * The order of a list (S6A-AC-53). Each order ends newest first, then by the ID, which makes it total, so a
     * page boundary neither repeats nor misses an issue (S6A-AC-54).
     */
    public enum Order {
        /** Newest first, the order without {@code sort}. */
        CREATED_AT(""),
        /** {@code sort=dueDate}: the earliest due date first and issues without one last. */
        DUE_DATE("due_date asc nulls last, "),
        /**
         * {@code sort=slaDue} (S8A-AC-32): the earliest running SLA due time of either goal first, and issues
         * without one last.
         */
        SLA_DUE("sla_due_at asc nulls last, ");

        private final String first;

        Order(String first) {
            this.first = first;
        }

        /** The ORDER BY list on the columns of {@code alias}, or on bare columns for an empty alias. */
        String by(String alias) {
            String a = alias.isEmpty() ? "" : alias + ".";
            return (first.isEmpty() ? "" : a + first) + a + "created_at desc, " + a + "number desc, " + a + "id";
        }
    }

    /** One page of a search and how many issues the search found in all. */
    public record Found(List<IssueRow> rows, long total) {}

    /** A WHERE condition with its parameters. */
    private record Condition(String sql, Object[] params) {}

    private final JdbcClient jdbc;

    IssueRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The issue with this key, if the viewer may see it. */
    public Optional<IssueRow> findByKey(Viewer viewer, IssueKey key) {
        return jdbc.sql(SELECT + "where c.code = ? and i.number = ? and " + IssueScope.SQL)
                .params(concat(new Object[] {key.companyCode(), key.number()}, IssueScope.params(viewer)))
                .query(IssueRepository::issue).optional();
    }

    /**
     * The issue with this key, if the viewer may see it, locked {@code FOR NO KEY UPDATE} until the end
     * of the transaction. A write under an issue calls this before any INSERT (architecture.md
     * "Concurrency and lock order", step 1).
     */
    public Optional<IssueRow> lockByKey(Viewer viewer, IssueKey key) {
        return jdbc.sql(SELECT + "where c.code = ? and i.number = ? and " + IssueScope.SQL + " for no key update of i")
                .params(concat(new Object[] {key.companyCode(), key.number()}, IssueScope.params(viewer)))
                .query(IssueRepository::issue).optional();
    }

    /**
     * The two issues with these IDs that the viewer may see, locked {@code FOR NO KEY UPDATE} until the
     * end of the transaction, in the order of their IDs as the database orders the uuid type (slice 6c
     * design section 0, 1). One statement, ordered by the ID, takes the two locks in that order whichever
     * issue the request names first, so two writes on the same two issues wait for each other instead of
     * deadlocking (S6C-AC-08). An issue the viewer may not see is not locked and not returned.
     *
     * @return the issues found, at most two, smaller ID first
     */
    public List<IssueRow> lockPair(Viewer viewer, UUID one, UUID other) {
        return jdbc.sql(SELECT + "where i.id in (?, ?) and " + IssueScope.SQL + " order by i.id for no key update of i")
                .params(concat(new Object[] {one, other}, IssueScope.params(viewer)))
                .query(IssueRepository::issue).list();
    }

    /** The assignee and the status of an issue, read without the viewer's scope. */
    public record Assignment(UUID issueId, Long assigneeId, IssueStatus status) {}

    /**
     * The assignee and the status of each of these issues that exists and is not in the trash or purged,
     * whoever may see it, in the order of their IDs. For telling the assignees of linked issues
     * (S6C-AC-31, S7A-AC-10); the caller checks who may see what.
     */
    List<Assignment> assignments(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select i.id, i.assignee_id, i.status from issue i where i.id = any(?) and i.deleted_at is null "
                        + "order by i.id")
                .param(ids.toArray(UUID[]::new))
                .query((rs, n) -> new Assignment(rs.getObject("id", UUID.class), rs.getObject("assignee_id", Long.class),
                        IssueStatus.valueOf(rs.getString("status")))).list();
    }

    /** The issue with this ID, if the viewer may see it. */
    public Optional<IssueRow> findById(Viewer viewer, UUID id) {
        return jdbc.sql(SELECT + "where i.id = ? and " + IssueScope.SQL)
                .params(concat(new Object[] {id}, IssueScope.params(viewer)))
                .query(IssueRepository::issue).optional();
    }

    /** Whether the viewer may see the issue with this ID. */
    public boolean visible(Viewer viewer, UUID id) {
        return jdbc.sql("select count(*) from issue i where i.id = ? and " + IssueScope.SQL)
                .params(concat(new Object[] {id}, IssueScope.params(viewer)))
                .query(Long.class).single() > 0;
    }

    /**
     * One page of the issues the viewer may see that match the filter, in {@code order}. For a filter
     * without a search text; with one, see {@link #search}.
     *
     * <p>The page is picked first, from the issue and its company only, and only its rows are joined to
     * the people. Written as one SELECT with the joins, the planner joined every issue the viewer sees
     * before it sorted: on 100,000 issues the admin's first page took 250 ms, 25 ms this way (slice 6a,
     * S6A-AC-55).
     */
    public List<IssueRow> list(Viewer viewer, IssueFilter filter, Order order, long offset, int limit) {
        Condition where = condition(viewer, filter);
        return jdbc.sql("with page as (select i.id from issue i join company c on c.id = i.company_id where "
                        + where.sql() + " order by " + order.by("i") + " offset ? limit ?) "
                        + "select " + COLUMNS + " from page p join issue i on i.id = p.id " + JOINS
                        + " order by " + order.by("i"))
                .params(concat(where.params(), offset, limit))
                .query(IssueRepository::issue).list();
    }

    /** How many issues {@link #list} has over all its pages: the same condition (S3-AC-06). */
    public long count(Viewer viewer, IssueFilter filter) {
        Condition where = condition(viewer, filter);
        return jdbc.sql("select count(*) from issue i join company c on c.id = i.company_id where " + where.sql())
                .params(where.params()).query(Long.class).single();
    }

    /**
     * One page of a search, newest first, and its total, in one statement (S3-AC-11). The search first
     * narrows the issues by the whole condition, kept apart ({@code MATERIALIZED}), and only then sorts
     * what it found. Written as one SELECT with {@code ORDER BY created_at DESC LIMIT}, the planner reads
     * the newest issues backward and tries the search text on each, which on 100,000 issues took 2
     * seconds for a text few issues hold (slice 3 design section 3). The total counts the same rows, so
     * a text many issues hold is searched once for the page and its count.
     *
     * <p>The page stands first in the FROM list on purpose. With nine relations the planner keeps the
     * written join order, and with the issue first it joined every issue to its company and people
     * before it looked at the page (200 ms on 100,000 issues for a page of 10).
     */
    public Found search(Viewer viewer, IssueFilter filter, Order order, long offset, int limit) {
        Condition where = condition(viewer, filter);
        long[] total = {0};
        List<IssueRow> rows = jdbc.sql("with found as materialized (select i.id, i.created_at, i.number, i.due_date, "
                        + "i.sla_due_at from issue i join company c on c.id = i.company_id where " + where.sql() + "), "
                        + "page as (select id from found order by " + order.by("") + " offset ? limit ?) "
                        + "select " + COLUMNS + ", (select count(*) from found) as total "
                        + "from page p join issue i on i.id = p.id " + JOINS + " order by " + order.by("i"))
                .params(concat(where.params(), offset, limit))
                .query((rs, n) -> {
                    total[0] = rs.getLong("total");
                    return issue(rs, n);
                }).list();
        // A page past the last one has no row to carry the total.
        return new Found(rows, rows.isEmpty() && offset > 0 ? count(viewer, filter) : total[0]);
    }

    /**
     * The condition of a list and of its count, on {@code i} and its company {@code c}: the viewer's
     * scope, and after it each part of the filter with {@code and}. No part stands in for the scope or
     * joins it with {@code or}, so a filter only narrows what the viewer sees (S3-AC-06, S3-AC-10). A
     * company code or an account the viewer cannot see matches nothing, like one that does not exist
     * (S3-AC-02, S3-AC-05); so does a label of a company the viewer cannot see, since only issues in
     * the scope are looked at (S6A-AC-52). The search text is a LIKE pattern in which {@code %}, {@code _}, and the
     * backslash find only themselves (S3-AC-13).
     */
    private static Condition condition(Viewer viewer, IssueFilter filter) {
        StringBuilder sql = new StringBuilder(IssueScope.SQL);
        List<Object> params = new ArrayList<>(List.of(IssueScope.params(viewer)));
        if (!filter.statuses().isEmpty()) {
            sql.append(" and i.status in (").append("?, ".repeat(filter.statuses().size() - 1)).append("?)");
            filter.statuses().forEach(status -> params.add(status.name()));
        }
        if (filter.companyCode() != null) {
            sql.append(" and c.code = ?");
            params.add(filter.companyCode());
        }
        if (filter.assignee() != null) {
            switch (filter.assignee().kind()) {
                case ACCOUNT -> {
                    sql.append(" and i.assignee_id = ?");
                    params.add(filter.assignee().accountId());
                }
                case NONE -> sql.append(" and i.assignee_id is null");
                case UNAVAILABLE -> sql.append(" and ").append(ASSIGNEE_UNAVAILABLE);
            }
        }
        if (filter.reporterId() != null) {
            sql.append(" and i.reporter_id = ?");
            params.add(filter.reporterId());
        }
        if (filter.q() != null) {
            sql.append(" and i.search_text ilike ? escape '\\'");
            params.add(AccountRepository.containing(filter.q()));
        }
        if (!filter.labelIds().isEmpty()) {
            sql.append(" and exists (select 1 from issue_label il where il.issue_id = i.id and il.label_id = any(?))");
            params.add(filter.labelIds().toArray(Long[]::new));
        }
        if (filter.sla() != null) {
            // The earliest due and warning times of the running goals are kept on the row (slice 8a design 0절 3).
            Timestamp now = Timestamp.from(filter.sla().now());
            switch (filter.sla().kind()) {
                case BREACHED -> {
                    sql.append(" and i.sla_due_at < ?");
                    params.add(now);
                }
                case WARNING -> {
                    sql.append(" and i.sla_warn_at <= ? and i.sla_due_at >= ?");
                    params.add(now);
                    params.add(now);
                }
            }
        }
        return new Condition(sql.toString(), params.toArray());
    }

    /**
     * How many issues that are not {@code CLOSED} have the account as their assignee, among those the
     * viewer may see; with {@code companyId}, in that company only (S3-AC-68). It counts what
     * {@link #updateAssigneesCleared} clears for the same account and company.
     */
    public long countOpenAssignments(Viewer viewer, long accountId, Long companyId) {
        Object[] params = concat(IssueScope.params(viewer), accountId);
        return jdbc.sql("select count(*) from issue i where " + IssueScope.SQL + " and " + OPEN_ASSIGNMENT
                        + (companyId == null ? "" : " and i.company_id = ?"))
                .params(companyId == null ? params : concat(params, companyId))
                .query(Long.class).single();
    }

    /**
     * The issue counts of one company (STATS-1): every issue, then by status, open being {@code RECEIVED} and
     * {@code CONFIRMED}. {@code sla} is null when the SLA goals were not asked for (S8B-AC-22).
     */
    public record CompanyCounts(String code, String name, CompanyStatus status, long total, long open,
            long inProgress, long resolved, long closed, CompanySla sla) {}

    /** The two SLA goals of a company's issues settled since a time (SLA-7, S8B-AC-23). */
    public record CompanySla(GoalCounts firstResponse, GoalCounts resolution) {}

    /**
     * One SLA goal of a company's issues (S8B-AC-23, S8B-AC-24).
     *
     * @param medianMinutes the lower median of the business time, in whole minutes rounded down; null without any
     */
    public record GoalCounts(Long medianMinutes, long met, long missed) {}

    /**
     * The SLA columns of {@link #countByCompany}, on the issue alias {@code i}, each with the parameter
     * {@code since} (slice 8b design.md 0절 15). A goal counts when it was settled as {@code MET} or {@code MISSED}
     * at or after {@code since}: the first response at {@code fr_settled_at}, the resolution when the issue was
     * resolved or closed, which a reopening clears (S8B-AC-23). The first response median takes only the goals a
     * response settled, whose settling time is the first response time (S8B-AC-24). {@code percentile_disc(0.5)}
     * is the lower median. An issue not under SLA has no outcome and counts nowhere.
     */
    private static final String SLA_COUNTS = ", "
            + "count(i.id) filter (where i.fr_outcome = 'MET' and i.fr_settled_at >= ?) as fr_met, "
            + "count(i.id) filter (where i.fr_outcome = 'MISSED' and i.fr_settled_at >= ?) as fr_missed, "
            + "percentile_disc(0.5) within group (order by i.fr_spent) filter (where i.fr_outcome in ('MET', 'MISSED') "
            + "and i.fr_settled_at >= ? and i.first_response_at = i.fr_settled_at) as fr_median, "
            + "count(i.id) filter (where i.res_outcome = 'MET' and coalesce(i.resolved_at, i.closed_at) >= ?) "
            + "as res_met, "
            + "count(i.id) filter (where i.res_outcome = 'MISSED' and coalesce(i.resolved_at, i.closed_at) >= ?) "
            + "as res_missed, "
            + "percentile_disc(0.5) within group (order by i.res_spent) filter (where i.res_outcome in ('MET', "
            + "'MISSED') and coalesce(i.resolved_at, i.closed_at) >= ?) as res_median ";

    /**
     * The issue counts of every company the viewer may see (STATS-1, STATS-2): every company for an admin, the
     * assigned ones for an agent, none for a customer. The company condition is the agent condition of
     * {@link IssueScope}; it sits on the company rather than the issue, so that a company without issues has an
     * item of zeros. Issues in the trash and purged ones are not counted, as {@link IssueScope} does not see them.
     * By open issues, the most first, then by code (S7B-AC-04).
     *
     * <p>With {@code slaSince}, each company also has its SLA goals settled since then ({@link #SLA_COUNTS}), counted
     * from the same issues in the same query; without it the query is that of slice 7b and {@code sla} is null.
     */
    public List<CompanyCounts> countByCompany(Viewer viewer, Instant slaSince) {
        String role = viewer.role().name();
        List<Object> params = new ArrayList<>();
        if (slaSince != null) {
            for (int i = 0; i < 6; i++) {
                params.add(Timestamp.from(slaSince));
            }
        }
        params.addAll(List.of(role, role, viewer.accountId()));
        return jdbc.sql("select c.code, c.name, c.status, count(i.id) as total, "
                        + "count(i.id) filter (where i.status in ('RECEIVED', 'CONFIRMED')) as open, "
                        + "count(i.id) filter (where i.status = 'IN_PROGRESS') as in_progress, "
                        + "count(i.id) filter (where i.status = 'RESOLVED') as resolved, "
                        + "count(i.id) filter (where i.status = 'CLOSED') as closed "
                        + (slaSince == null ? "" : SLA_COUNTS)
                        + "from company c left join issue i on i.company_id = c.id and i.deleted_at is null "
                        + "where (? = 'ADMIN' or (? = 'AGENT' and exists (select 1 from company_agent ca "
                        + "where ca.company_id = c.id and ca.account_id = ?))) "
                        + "group by c.id order by open desc, c.code")
                .params(params)
                .query((rs, row) -> new CompanyCounts(rs.getString("code"), rs.getString("name"),
                        CompanyStatus.valueOf(rs.getString("status")), rs.getLong("total"), rs.getLong("open"),
                        rs.getLong("in_progress"), rs.getLong("resolved"), rs.getLong("closed"),
                        slaSince == null ? null : new CompanySla(goal(rs, "fr_"), goal(rs, "res_"))))
                .list();
    }

    private static GoalCounts goal(ResultSet rs, String prefix) throws SQLException {
        Long seconds = rs.getObject(prefix + "median", Long.class);
        return new GoalCounts(seconds == null ? null : Math.floorDiv(seconds, 60L), rs.getLong(prefix + "met"),
                rs.getLong(prefix + "missed"));
    }

    // Writes

    /**
     * A new issue with the number the company counter gave it (ISSUE-3), and the text the search looks in.
     * {@code createdBy} is the staff member who files it for the reporter, or null (ISSUE-9). {@code sla} is
     * null for an issue filed while SLA is off (S8A-AC-11).
     */
    void insert(UUID id, long companyId, int number, String title, String content, Priority priority,
            boolean privateIssue, long reporterId, Long createdBy, String channel, Instant now, IssueClocks sla) {
        jdbc.sql("insert into issue (id, tenant_id, company_id, number, title, content, status, priority, is_private, "
                        + "reporter_id, created_by, channel, version, created_at, updated_at, search_text"
                        + (sla == null ? "" : ", " + IssueClocks.INSERT) + ") "
                        + "values (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?"
                        + (sla == null ? "" : ", ?".repeat(sla.params().length)) + ")")
                .params(withClocks(new Object[] {id, companyId, number, title, content, IssueStatus.RECEIVED.name(),
                        priority.name(), privateIssue, reporterId, createdBy, channel, Timestamp.from(now),
                        Timestamp.from(now), SearchText.of(title, content)}, sla))
                .update();
    }

    /** {@code , <the SLA columns> = ?} for an UPDATE that also writes the clocks, or nothing without them. */
    private static String clocks(IssueClocks sla) {
        return sla == null ? "" : ", " + IssueClocks.SET;
    }

    /** {@code first}, then the parameters of the clocks when there are any, then {@code last}. */
    private static Object[] withClocks(Object[] first, IssueClocks sla, Object... last) {
        Object[] middle = sla == null ? new Object[0] : sla.params();
        return concat(concat(first, middle), last);
    }

    /**
     * Sets the assignee, or clears it with null. The caller has locked the issue row and seen that the
     * assignee changes. A change to the row raises its version (S3-AC-24).
     */
    void updateAssignee(UUID id, Long assigneeId, Instant now) {
        jdbc.sql("update issue set assignee_id = ?, updated_at = ?, version = version + 1 where id = ?")
                .params(assigneeId, Timestamp.from(now), id)
                .update();
    }

    /**
     * Sets the due date, or clears it with null (DUE-1). The caller has locked the issue row and seen that
     * the date changes. A change to the row raises its version (slice 6a design section 0, 5).
     */
    void updateDueDate(UUID id, LocalDate dueDate, Instant now) {
        jdbc.sql("update issue set due_date = ?, updated_at = ?, version = version + 1 where id = ?")
                .params(dueDate, Timestamp.from(now), id)
                .update();
    }

    /**
     * Raises the version of an issue whose labels changed (slice 6a design section 0, 5). The caller has
     * locked the issue row.
     */
    void updateVersion(UUID id, Instant now) {
        jdbc.sql("update issue set updated_at = ?, version = version + 1 where id = ?")
                .params(Timestamp.from(now), id)
                .update();
    }

    /**
     * Clears the assignee of every issue that is not {@code CLOSED} and has this account as its assignee;
     * with {@code companyId}, in that company only (ACCT-7, S3-AC-69). One conditional UPDATE, without
     * locking the rows one by one first (STATUS-7): an issue that got another assignee or was closed
     * meanwhile no longer matches and stays as it is. Each cleared issue gets a new version. An issue in
     * the trash keeps its assignee, to be restored as it was (slice 7a acceptance section 5, 6).
     *
     * @return the issues that were cleared
     */
    public List<UUID> updateAssigneesCleared(long accountId, Long companyId, Instant now) {
        List<Object> params = new ArrayList<>(List.of(Timestamp.from(now), accountId));
        if (companyId != null) {
            params.add(companyId);
        }
        return jdbc.sql("update issue i set assignee_id = null, updated_at = ?, version = version + 1 where "
                        + "i.deleted_at is null and " + OPEN_ASSIGNMENT + (companyId == null ? "" : " and i.company_id = ?") + " returning i.id")
                .params(params)
                .query(UUID.class).list();
    }

    /**
     * Sets the first response time once (S0-AC-46): only while it is empty, so a later staff comment
     * changes nothing. A change to the row raises its version (S0-AC-45). With {@code sla}, the SLA clocks
     * are written in the same UPDATE (S8A-AC-13); the caller has locked the row.
     */
    public void updateFirstResponse(UUID id, Instant at, IssueClocks sla) {
        jdbc.sql("update issue set first_response_at = ?, updated_at = ?, version = version + 1" + clocks(sla)
                        + " where id = ? and first_response_at is null")
                .params(withClocks(new Object[] {Timestamp.from(at), Timestamp.from(at)}, sla, id))
                .update();
    }

    /**
     * Moves the issue from one status to another, if it still has the first one (STATUS-7): a change
     * that came in between leaves this one without a row to change, and the caller reads the issue
     * again. {@code RESOLVED} sets the resolved time and {@code CLOSED} the closed time; {@code CLOSED}
     * keeps the resolved time, every other status clears both (STATUS-6, STATUS-4). {@code RESOLVED} also
     * sets the time to close from, and every other status clears it (S7A-AC-41). An issue in the trash
     * does not change either (S7A-AC-12): it may have been deleted after the caller read it.
     *
     * @param closedAfter for a reopening: a {@code CLOSED} issue only changes when it was closed after
     *     this time; null for every other change
     * @param sla the SLA clocks after the change, written in the same UPDATE (S8A-AC-14, S8A-AC-15); null for
     *     an issue not under SLA
     * @return whether the issue changed
     */
    boolean updateStatus(UUID id, IssueStatus from, IssueStatus to, Instant now, Instant closedAfter,
            IssueClocks sla) {
        Timestamp at = Timestamp.from(now);
        List<Object> params = new ArrayList<>(List.of(to.name()));
        String times = switch (to) {
            case RESOLVED -> {
                params.add(at);
                params.add(at);
                yield "resolved_at = ?, closed_at = null, auto_close_from = ?";
            }
            case CLOSED -> {
                params.add(at);
                yield "closed_at = ?, auto_close_from = null";
            }
            default -> "resolved_at = null, closed_at = null, auto_close_from = null";
        };
        params.add(at);
        if (sla != null) {
            params.addAll(Arrays.asList(sla.params()));
        }
        params.addAll(List.of(id, from.name()));
        String stillOpenable = "";
        if (closedAfter != null) {
            stillOpenable = " and (status <> 'CLOSED' or closed_at > ?)";
            params.add(Timestamp.from(closedAfter));
        }
        return jdbc.sql("update issue set status = ?, " + times + ", updated_at = ?, version = version + 1"
                        + clocks(sla) + " where id = ? and status = ? and deleted_at is null" + stillOpenable)
                .params(params)
                .update() == 1;
    }

    /**
     * Stores the title, the content, and the priority of an edit, and the text the search looks in
     * (S3-AC-16). The caller has locked the issue row and checked its version. With {@code sla}, the SLA
     * clocks of the new priority are written in the same UPDATE (S8A-AC-17).
     */
    void updateFields(UUID id, String title, String content, Priority priority, Instant now, IssueClocks sla) {
        jdbc.sql("update issue set title = ?, content = ?, priority = ?, search_text = ?, updated_at = ?, "
                        + "version = version + 1" + clocks(sla) + " where id = ?")
                .params(withClocks(new Object[] {title, content, priority.name(), SearchText.of(title, content),
                        Timestamp.from(now)}, sla, id))
                .update();
    }

    // The trash (TRASH-1 to TRASH-4) and the automatic closing (STATUS-5). An issue in the trash is out of
    // IssueScope, so these read it with a condition of their own: admins only, as the trash paths are.

    /** "In the trash", as SQL on {@code i}: deleted and not purged yet. */
    private static final String IN_TRASH = "i.deleted_at is not null and i.purged_at is null";

    /** Only an admin reads the trash, as SQL with the parameter of {@link #trashParams}. */
    private static final String TRASH_READER = "? = 'ADMIN'";

    /** A live {@code RESOLVED} issue due to close, as SQL on {@code i} with one parameter, the latest time to close from. */
    private static final String DUE_FOR_CLOSING = "i.status = 'RESOLVED' and i.auto_close_from <= ? "
            + "and i.deleted_at is null";

    private static final String TRASH_SELECT = "select i.id, c.code, c.name, i.number, i.title, i.status, "
            + "i.priority, " + IssueClocks.COLUMNS + ", "
            + "i.deleted_at, i.purged_at, i.deleted_by, db.name as deleted_by_name, db.role as deleted_by_role, "
            + "dbp.updated_at as deleted_by_photo_updated_at from issue i join company c on c.id = i.company_id "
            + "left join account db on db.id = i.deleted_by "
            + "left join account_photo dbp on dbp.account_id = i.deleted_by ";

    private static Object[] trashParams(Viewer viewer) {
        return new Object[] {viewer.role().name()};
    }

    /**
     * An issue as the trash sees it: its key, title, company, status, and when and by whom it was deleted
     * ({@code deletedBy} null when it is not deleted). {@code deletedAt} is null for a live issue and
     * {@code purgedAt} null unless it was purged. {@code sla} is null when the issue is not under SLA.
     */
    public record TrashedRow(UUID id, String companyCode, String companyName, int number, String title,
            IssueStatus status, Priority priority, IssueClocks sla, Instant deletedAt, Staff deletedBy,
            Instant purgedAt) {

        public String key() {
            return companyCode + "-" + number;
        }

        /** Deleted and not purged yet. */
        public boolean inTrash() {
            return deletedAt != null && purgedAt == null;
        }
    }

    /**
     * One page of the issues in the trash, the latest deleted first and of those deleted at once the
     * highest number first (S7A-AC-20); empty unless the viewer is an admin. The company code and the ID
     * last make the order total, so a page boundary neither repeats nor misses an issue.
     */
    public List<TrashedRow> listTrash(Viewer viewer, long offset, int limit) {
        return jdbc.sql(TRASH_SELECT + "where " + IN_TRASH + " and " + TRASH_READER
                        + " order by i.deleted_at desc, i.number desc, c.code, i.id offset ? limit ?")
                .params(concat(trashParams(viewer), offset, limit))
                .query(IssueRepository::trashed).list();
    }

    /** How many issues {@link #listTrash} has over all its pages. */
    public long countTrash(Viewer viewer) {
        return jdbc.sql("select count(*) from issue i where " + IN_TRASH + " and " + TRASH_READER)
                .params(trashParams(viewer)).query(Long.class).single();
    }

    /** The issue in the trash with this key, if the viewer is an admin; not locked. */
    public Optional<TrashedRow> findInTrash(Viewer viewer, IssueKey key) {
        return jdbc.sql(TRASH_SELECT + "where c.code = ? and i.number = ? and " + IN_TRASH + " and " + TRASH_READER)
                .params(concat(new Object[] {key.companyCode(), key.number()}, trashParams(viewer)))
                .query(IssueRepository::trashed).optional();
    }

    /**
     * The issues with these IDs, live, in the trash, or purged, locked {@code FOR NO KEY UPDATE} in the
     * order of their IDs as the database orders the uuid type, in one statement, if the viewer is an admin.
     * A restore locks the issue and every issue linked to it this way (S7A-AC-24), like a link locks two
     * (S6C-AC-08); a purge locks the one issue. The caller looks at whether the issue is in the trash under
     * the lock (TRASH-2, TRASH-3).
     *
     * @return the issues found, in ID order
     */
    public List<TrashedRow> lockTrashedByIds(Viewer viewer, Collection<UUID> ids) {
        return jdbc.sql(TRASH_SELECT + "where i.id = any(?) and " + TRASH_READER
                        + " order by i.id for no key update of i")
                .params(concat(new Object[] {ids.toArray(UUID[]::new)}, trashParams(viewer)))
                .query(IssueRepository::trashed).list();
    }

    /**
     * The issues deleted at or before {@code deletedUntil} and not purged yet, the earliest deleted first,
     * at most {@code limit}, if the viewer is an admin; not locked (TRASH-3).
     */
    public List<UUID> findDueForPurge(Viewer viewer, Instant deletedUntil, int limit) {
        return jdbc.sql("select i.id from issue i where " + IN_TRASH + " and i.deleted_at <= ? and " + TRASH_READER
                        + " order by i.deleted_at, i.id limit ?")
                .params(Timestamp.from(deletedUntil), viewer.role().name(), limit)
                .query(UUID.class).list();
    }

    /**
     * Moves a live issue to the trash (S7A-AC-01): the time, the admin, and a new version. The caller has
     * locked the row in its scope. With {@code sla}, the paused SLA clocks are written in the same UPDATE
     * (S8A-AC-16).
     */
    public void updateDeleted(UUID id, long deletedBy, Instant now, IssueClocks sla) {
        jdbc.sql("update issue set deleted_at = ?, deleted_by = ?, updated_at = ?, version = version + 1"
                        + clocks(sla) + " where id = ? and deleted_at is null")
                .params(withClocks(new Object[] {Timestamp.from(now), deletedBy, Timestamp.from(now)}, sla, id))
                .update();
    }

    /**
     * Takes an issue out of the trash (S7A-AC-21) with a new version. A {@code RESOLVED} issue closes 7
     * days after now, not 7 days after it was resolved (S7A-AC-22). The caller has locked the row and seen
     * it in the trash. With {@code sla}, the SLA clocks running on are written in the same UPDATE (S8A-AC-16).
     * {@code restored_at} becomes now: automatic cleanup counts its period from then (S10B-AC-31).
     */
    public void updateRestored(UUID id, Instant now, IssueClocks sla) {
        jdbc.sql("update issue i set deleted_at = null, deleted_by = null, updated_at = ?, restored_at = ?, "
                        + "version = version + 1, "
                        + "auto_close_from = case when i.status = 'RESOLVED' then cast(? as timestamptz) end"
                        + clocks(sla) + " where i.id = ? and " + IN_TRASH)
                .params(withClocks(new Object[] {Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)}, sla,
                        id))
                .update();
    }

    /**
     * Empties a purged issue (TRASH-3, S7A-AC-30): an empty title, content, and search text, and the time
     * it was purged. The row with its key, company, status, and times stays; the version does not change,
     * as nobody reads the issue again. The caller has locked the row and seen it in the trash.
     */
    public void updatePurged(UUID id, Instant now) {
        jdbc.sql("update issue i set title = '', content = '', search_text = '', purged_at = ? where i.id = ? and "
                        + IN_TRASH)
                .params(Timestamp.from(now), id)
                .update();
    }

    /**
     * Up to {@code limit} live {@code RESOLVED} issues whose time to close from is at or before
     * {@code until}, in ID order, locked {@code FOR NO KEY UPDATE}; a row another transaction holds is
     * skipped (STATUS-5, slice 7a design section 4). The automatic closing takes them one batch at a time.
     */
    List<UUID> lockDueForClosing(Instant until, int limit) {
        return jdbc.sql("select i.id from issue i where " + DUE_FOR_CLOSING + " order by i.id limit ? "
                        + "for no key update skip locked")
                .params(Timestamp.from(until), limit)
                .query(UUID.class).list();
    }

    /**
     * Closes these issues as {@link #updateStatus} closes one, on the conditions of
     * {@link #lockDueForClosing} again (STATUS-5, STATUS-7): an issue reopened, deleted, or resolved anew
     * meanwhile does not change.
     *
     * @return the issues that were closed
     */
    List<UUID> updateClosedAutomatically(List<UUID> ids, Instant until, Instant now) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("update issue i set status = 'CLOSED', closed_at = ?, auto_close_from = null, updated_at = ?, "
                        + "version = version + 1 where i.id = any(?) and " + DUE_FOR_CLOSING + " returning i.id")
                .params(Timestamp.from(now), Timestamp.from(now), ids.toArray(UUID[]::new), Timestamp.from(until))
                .query(UUID.class).list();
    }

    private static TrashedRow trashed(ResultSet rs, int row) throws SQLException {
        Timestamp deleted = rs.getTimestamp("deleted_at");
        Timestamp purged = rs.getTimestamp("purged_at");
        return new TrashedRow(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getInt("number"), rs.getString("title"), IssueStatus.valueOf(rs.getString("status")),
                Priority.valueOf(rs.getString("priority")), IssueClocks.read(rs),
                deleted == null ? null : deleted.toInstant(), staff(rs, "deleted_by", "deleted_by"),
                purged == null ? null : purged.toInstant());
    }

    private static IssueRow issue(ResultSet rs, int row) throws SQLException {
        Timestamp firstResponse = rs.getTimestamp("first_response_at");
        Timestamp resolved = rs.getTimestamp("resolved_at");
        Timestamp closed = rs.getTimestamp("closed_at");
        Timestamp reporterPhoto = rs.getTimestamp("reporter_photo_updated_at");
        return new IssueRow(rs.getObject("id", UUID.class), rs.getLong("company_id"), rs.getString("code"),
                rs.getString("name"), rs.getInt("number"), rs.getString("title"), rs.getString("content"),
                IssueStatus.valueOf(rs.getString("status")), Priority.valueOf(rs.getString("priority")),
                rs.getBoolean("is_private"), rs.getLong("reporter_id"), rs.getString("reporter_name"),
                Role.valueOf(rs.getString("reporter_role")), reporterPhoto == null ? null : reporterPhoto.toInstant(),
                staff(rs, "created_by", "created_by"), rs.getString("channel"),
                staff(rs, "assignee_id", "assignee"), rs.getBoolean("assignee_unavailable"),
                firstResponse == null ? null : firstResponse.toInstant(),
                resolved == null ? null : resolved.toInstant(), closed == null ? null : closed.toInstant(),
                rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getObject("due_date", LocalDate.class), IssueClocks.read(rs));
    }

    /** The account in column {@code id}, with its name, role, and photo time under {@code prefix}; null when empty. */
    private static Staff staff(ResultSet rs, String id, String prefix) throws SQLException {
        long accountId = rs.getLong(id);
        if (rs.wasNull()) {
            return null;
        }
        Timestamp photo = rs.getTimestamp(prefix + "_photo_updated_at");
        return new Staff(accountId, rs.getString(prefix + "_name"), Role.valueOf(rs.getString(prefix + "_role")),
                photo == null ? null : photo.toInstant());
    }
}
