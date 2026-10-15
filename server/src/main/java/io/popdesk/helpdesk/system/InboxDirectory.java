package io.popdesk.helpdesk.system;

import static io.popdesk.helpdesk.issue.IssueScope.concat;

import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.account.AccountRepository;
import io.popdesk.helpdesk.issue.IssueScope;
import io.popdesk.notification.CommentFacts;
import io.popdesk.notification.InboxFacts;
import io.popdesk.notification.IssueFacts.Issue;
import io.popdesk.notification.TaskFacts;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What the notification module reads about issues, people, comments, and tasks for the inbox and just
 * before it sends an email about a comment or a task (INBOX-3, MAIL-4, S6B-AC-34). System reads by ID. The one that says what an
 * account may see, {@link #visible}, applies {@link IssueScope} with the account's role as it is now,
 * like {@link IssueVisibility}.
 */
@Component
class InboxDirectory implements InboxFacts, CommentFacts, TaskFacts {

    private final AccountRepository accounts;
    private final JdbcClient jdbc;

    InboxDirectory(AccountRepository accounts, JdbcClient jdbc) {
        this.accounts = accounts;
        this.jdbc = jdbc;
    }

    @Override
    public Set<UUID> visible(long accountId, Collection<UUID> issueIds) {
        if (issueIds.isEmpty()) {
            return Set.of();
        }
        Viewer viewer = accounts.findById(accountId).map(a -> new Viewer(a.id(), a.role())).orElse(null);
        if (viewer == null) {
            return Set.of();
        }
        return Set.copyOf(jdbc.sql("select i.id from issue i where i.id = any(?) and " + IssueScope.SQL)
                .params(concat(new Object[] {issueIds.toArray(UUID[]::new)}, IssueScope.params(viewer)))
                .query(UUID.class).list());
    }

    @Override
    public Map<UUID, Issue> issues(Collection<UUID> issueIds) {
        Map<UUID, Issue> found = new HashMap<>();
        if (issueIds.isEmpty()) {
            return found;
        }
        jdbc.sql("select i.id, c.code, i.number, i.title from issue i join company c on c.id = i.company_id "
                        + "where i.id = any(?)")
                .param(issueIds.toArray(UUID[]::new))
                .query(rs -> {
                    found.put(rs.getObject("id", UUID.class), new Issue(rs.getString("code") + "-"
                            + rs.getInt("number"), rs.getString("title"), rs.getString("code")));
                });
        return found;
    }

    /**
     * The rule of the portal's own responses (ADR D-49): to a customer a customer is a person, and an
     * agent or an admin is the support team, without name, ID, or anything of a photo.
     */
    @Override
    public Map<Long, Actor> actors(Collection<Long> accountIds, Reader reader) {
        Map<Long, Actor> found = new HashMap<>();
        if (accountIds.isEmpty()) {
            return found;
        }
        jdbc.sql("select a.id, a.name, a.role, p.updated_at from account a "
                        + "left join account_photo p on p.account_id = a.id where a.id = any(?)")
                .param(new HashSet<>(accountIds).toArray(Long[]::new))
                .query(rs -> {
                    Role role = Role.valueOf(rs.getString("role"));
                    Timestamp photo = rs.getTimestamp("updated_at");
                    found.put(rs.getLong("id"), reader == Reader.CUSTOMER && role != Role.CUSTOMER
                            ? new Actor(ActorKind.SUPPORT_TEAM, null, null, null)
                            : new Actor(ActorKind.valueOf(role.name()), rs.getLong("id"), rs.getString("name"),
                                    photo == null ? null : photo.toInstant()));
                });
        return found;
    }

    @Override
    public Map<Long, String> taskTitles(Collection<Long> taskIds) {
        Map<Long, String> found = new HashMap<>();
        if (taskIds.isEmpty()) {
            return found;
        }
        jdbc.sql("select id, title from task where id = any(?) and deleted_at is null")
                .param(new HashSet<>(taskIds).toArray(Long[]::new))
                .query(rs -> {
                    found.put(rs.getLong("id"), rs.getString("title"));
                });
        return found;
    }

    @Override
    public Optional<String> titleForAssignee(long taskId, long accountId) {
        return jdbc.sql("select title from task where id = ? and assignee_id = ? and deleted_at is null")
                .params(taskId, accountId).query(String.class).optional();
    }

    @Override
    public boolean present(long commentId) {
        return jdbc.sql("select exists (select 1 from comment where id = ? and deleted_at is null)").param(commentId)
                .query(Boolean.class).single();
    }

    @Override
    public boolean mentions(long commentId, long accountId) {
        return jdbc.sql("select exists (select 1 from comment_mention where comment_id = ? and account_id = ?)")
                .params(commentId, accountId).query(Boolean.class).single();
    }
}
