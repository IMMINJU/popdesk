package io.popdesk.helpdesk.issue;

import static io.popdesk.helpdesk.issue.IssueScope.concat;

import io.popdesk.helpdesk.LinkType;
import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.Viewer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The history of issues (table {@code issue_history}, HIST-1): one {@code CREATED} row when an issue
 * is filed, then one {@code CHANGED} row for each field that really changed. A row is written in the
 * transaction of the change it records (HIST-2). Reads take the {@link Viewer} and apply
 * {@link IssueScope} like every read under an issue.
 */
@Repository
public class IssueHistoryRepository {

    /** The fields a {@code CHANGED} row can name. The names are stored and are part of the API. */
    public enum Field {
        STATUS,
        /** Both values are account IDs as text; null for nobody. */
        ASSIGNEE,
        TITLE,
        CONTENT,
        PRIORITY,
        /**
         * An attachment was added to the issue or deleted (ATT-8): its ID is the new value when it was
         * added and the old value when it was deleted, the other one null. Never its file name, which a
         * deleted attachment gives up (S5-AC-43).
         */
        ATTACHMENT,
        /**
         * A label was put on the issue or taken off (S6A-AC-60): its name at that time is the new value
         * when it was put on and the old value when it was taken off, the other one null. Never its ID,
         * which says nothing once the label is deleted. Changing or deleting the label itself leaves no
         * entry (S6A-AC-62).
         */
        LABEL,
        /** The due date was set, changed, or cleared (S6A-AC-61): {@code YYYY-MM-DD}, null for none. */
        DUE_DATE,
        /**
         * A link to another issue was made or removed (LINK-7, S6C-AC-20): {@link LinkHistoryValue} is the
         * new value when it was made and the old value when it was removed, the other one null. Only the
         * other issue's ID, never its key or title.
         */
        LINK,
        /**
         * The issue was deleted into the trash, restored from it, or purged (S7A-AC-06): {@link #TRASHED}
         * is the new value of a delete and the old value of a restore and of a purge; {@link #PURGED} the
         * new value of a purge; the other one null.
         */
        TRASH;

        /** The value of {@link #TRASH} for an issue in the trash. */
        public static final String TRASHED = "TRASHED";

        /** The value of {@link #TRASH} for a purged issue. */
        public static final String PURGED = "PURGED";
    }

    /** An account as a history entry names it, by its name today. */
    public record Person(long accountId, String name, Role role, Instant photoUpdatedAt) {}

    /** A link a {@code LINK} entry names: its type seen from the issue, and the other issue as it is now. */
    public record HistoryLink(LinkType type, String key, String title) {}

    /**
     * One entry. For {@code ASSIGNEE} the two people stand in for the values, and for {@code LINK} the two
     * links (S6C-AC-21); the values are null then. Every other field has its values as text and neither.
     * The actor's name and role are null for popdesk itself (account 0).
     */
    public record HistoryRow(long id, String kind, String field, String oldValue, String newValue, Person oldPerson,
            Person newPerson, long actorId, String actorName, Role actorRole, Instant actorPhotoUpdatedAt,
            Instant createdAt, HistoryLink oldLink, HistoryLink newLink) {}

    private static final String ASSIGNEE = "'" + Field.ASSIGNEE.name() + "'";
    private static final String LINK = "'" + Field.LINK.name() + "'";

    /**
     * The entries of one issue {@code i} with the other issue {@code li} of a {@code LINK} entry and its
     * company {@code lc}. CASE, not AND: a value of another field must never reach the cast.
     */
    private static final String FROM = "from issue_history h join issue i on i.id = h.issue_id "
            + "left join issue li on li.id = case when h.field = " + LINK
            + " then split_part(coalesce(h.new_value, h.old_value), ':', 2)::uuid end "
            + "left join company lc on lc.id = li.company_id ";

    /**
     * The entries of the issue if the viewer may see it, and of its {@code LINK} entries only those whose
     * other issue the viewer may see now (S6C-AC-22); with the parameters of {@link #where}.
     * {@code is distinct from}, not {@code <>}: a {@code CREATED} entry has no field.
     */
    private static final String WHERE = "where h.issue_id = ? and " + IssueScope.SQL + " and (h.field is distinct from "
            + LINK + " or (li.id is not null and " + IssueScope.on("li") + ")) ";

    // CASE, not AND: a value that is no account ID must never reach the cast.
    private static final String SELECT = "select h.id, h.kind, h.field, h.old_value, h.new_value, h.actor_id, "
            + "h.created_at, a.name as actor_name, a.role as actor_role, ap.updated_at as actor_photo_updated_at, "
            + "o.id as old_id, o.name as old_name, o.role as old_role, op.updated_at as old_photo_updated_at, "
            + "n.id as new_id, n.name as new_name, n.role as new_role, np.updated_at as new_photo_updated_at, "
            + "lc.code || '-' || li.number as link_key, li.title as link_title "
            + FROM
            + "left join account a on a.id = h.actor_id left join account_photo ap on ap.account_id = a.id "
            + "left join account o on o.id = case when h.field = " + ASSIGNEE + " then h.old_value::bigint end "
            + "left join account_photo op on op.account_id = o.id "
            + "left join account n on n.id = case when h.field = " + ASSIGNEE + " then h.new_value::bigint end "
            + "left join account_photo np on np.account_id = n.id ";

    private final JdbcClient jdbc;

    IssueHistoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One page of the history of the issue, if the viewer may see the issue, oldest first (HIST-3); a
     * {@code LINK} entry only while the viewer may see its other issue (S6C-AC-22).
     */
    public List<HistoryRow> list(Viewer viewer, UUID issueId, long offset, int limit) {
        return jdbc.sql(SELECT + WHERE + "order by h.id offset ? limit ?")
                .params(concat(where(viewer, issueId), offset, limit))
                .query(IssueHistoryRepository::entry).list();
    }

    /** How many entries {@link #list} has over all its pages: the same condition. */
    public long count(Viewer viewer, UUID issueId) {
        return jdbc.sql("select count(*) " + FROM + WHERE)
                .params(where(viewer, issueId))
                .query(Long.class).single();
    }

    /** The parameters of {@link #WHERE}: the issue, the scope of the issue, the scope of the linked issue. */
    private static Object[] where(Viewer viewer, UUID issueId) {
        return concat(concat(new Object[] {issueId}, IssueScope.params(viewer)), IssueScope.params(viewer));
    }

    // Writes

    /**
     * The first entry of an issue: who filed it, without a field or values (the title is not repeated
     * here). Called after the issue row is written.
     */
    public void insertCreated(UUID issueId, long actorId, Instant now) {
        insert(issueId, "CREATED", null, null, null, actorId, now);
    }

    /** One changed field. The caller writes this only when the value really changed (HIST-2). */
    public void insertChanged(UUID issueId, Field field, String oldValue, String newValue, long actorId, Instant now) {
        insert(issueId, "CHANGED", field.name(), oldValue, newValue, actorId, now);
    }

    private void insert(UUID issueId, String kind, String field, String oldValue, String newValue, long actorId,
            Instant now) {
        jdbc.sql("insert into issue_history (tenant_id, issue_id, kind, field, old_value, new_value, actor_id, "
                        + "created_at) values (1, ?, ?, ?, ?, ?, ?, ?)")
                .params(issueId, kind, field, oldValue, newValue, actorId, Timestamp.from(now))
                .update();
    }

    private static HistoryRow entry(ResultSet rs, int row) throws SQLException {
        String field = rs.getString("field");
        boolean link = Field.LINK.name().equals(field);
        boolean textless = link || Field.ASSIGNEE.name().equals(field);
        String oldValue = rs.getString("old_value");
        String newValue = rs.getString("new_value");
        String actorRole = rs.getString("actor_role");
        return new HistoryRow(rs.getLong("id"), rs.getString("kind"), field,
                textless ? null : oldValue, textless ? null : newValue,
                person(rs, "old"), person(rs, "new"), rs.getLong("actor_id"), rs.getString("actor_name"),
                actorRole == null ? null : Role.valueOf(actorRole), instant(rs, "actor_photo_updated_at"),
                rs.getTimestamp("created_at").toInstant(), link ? link(rs, oldValue) : null,
                link ? link(rs, newValue) : null);
    }

    /** One side of a {@code LINK} entry, with the other issue as it is now; null for a side without a value. */
    private static HistoryLink link(ResultSet rs, String value) throws SQLException {
        LinkHistoryValue.Value read = LinkHistoryValue.parse(value).orElse(null);
        return read == null ? null : new HistoryLink(read.type(), rs.getString("link_key"), rs.getString("link_title"));
    }

    private static Person person(ResultSet rs, String prefix) throws SQLException {
        String role = rs.getString(prefix + "_role");
        return role == null ? null : new Person(rs.getLong(prefix + "_id"), rs.getString(prefix + "_name"),
                Role.valueOf(role), instant(rs, prefix + "_photo_updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
