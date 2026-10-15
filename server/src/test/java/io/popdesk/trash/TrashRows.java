package io.popdesk.trash;

import io.popdesk.support.DbInspector;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads the rows of one issue and of what is under it, for what HTTP cannot show while the issue is in the trash:
 * every issue path answers 404 then, and restoring is another tab's test. Read-only, through {@link DbInspector}.
 *
 * <p>It names tables and columns that slices before 7a made ({@code issue}, {@code company}, {@code comment},
 * {@code attachment}, {@code task}, {@code task_note}, {@code issue_link}, {@code issue_label}, {@code outbox},
 * {@code notification}, {@code issue_history}), never the columns 7a adds: whether an issue is in the trash is read
 * through the API (the trash list, or a 404). An issue is found by its key: the company's code and the number.
 *
 * <p>Public, so that the tests of other packages and tabs can use it.
 */
public final class TrashRows {

    private final DbInspector db;

    public TrashRows(DbInspector db) {
        this.db = db;
    }

    /** The issue's row, every column, by its key {@code CODE-n}. */
    public Map<String, Object> issue(String key) {
        int dash = key.lastIndexOf('-');
        if (dash <= 0) {
            throw new AssertionError("Not an issue key: " + key);
        }
        return db.row("select i.* from issue i join company c on c.id = i.company_id where c.code = ? and i.number = ?",
                key.substring(0, dash), Integer.parseInt(key.substring(dash + 1)));
    }

    /** The issue's ID. */
    public UUID id(String key) {
        return (UUID) issue(key).get("id");
    }

    /**
     * What is under the issue, each as the rows of one kind with the columns that hold what was written and who it is
     * for, in ID order: comments, attachments, tasks, task notes, links, labels, mentions. Two snapshots are equal when
     * nothing under the issue changed.
     */
    public Map<String, List<Map<String, Object>>> under(String key) {
        UUID id = id(key);
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        out.put("comments", db.rows("select id, internal, content, author_id from comment where issue_id = ? "
                + "order by id", id));
        out.put("attachments", db.rows("select id::text as id, comment_id, file_name, state from attachment "
                + "where issue_id = ? order by id", id));
        out.put("tasks", db.rows("select id, title, status, assignee_id, due_date, deleted_at from task "
                + "where issue_id = ? order by id", id));
        out.put("task notes", db.rows("select n.id, n.kind, n.content, n.deleted_at from task_note n "
                + "join task t on t.id = n.task_id where t.issue_id = ? order by n.id", id));
        out.put("links", db.rows("select id, low_issue_id::text as low, high_issue_id::text as high, type, "
                + "duplicate_issue_id::text as duplicate from issue_link where low_issue_id = ? or high_issue_id = ? "
                + "order by id", id, id));
        out.put("labels", db.rows("select label_id from issue_label where issue_id = ? order by label_id", id));
        out.put("mentions", db.rows("select m.comment_id, m.account_id from comment_mention m "
                + "join comment c on c.id = m.comment_id where c.issue_id = ? order by 1, 2", id));
        return out;
    }

    /**
     * The issue's own fields that a delete must leave: title, content, status, priority, privacy, assignee, due date,
     * reporter, company, number.
     */
    public Map<String, Object> fields(String key) {
        Map<String, Object> row = issue(key);
        Map<String, Object> out = new LinkedHashMap<>();
        for (String column : List.of("title", "content", "status", "priority", "is_private", "assignee_id",
                "due_date", "reporter_id", "company_id", "number", "resolved_at", "closed_at")) {
            if (row.containsKey(column)) {
                out.put(column, row.get(column));
            }
        }
        return out;
    }

    /** The issue's {@code version}. */
    public long version(String key) {
        return ((Number) issue(key).get("version")).longValue();
    }

    /** The send queue rows of the issue, mail and webhook, with their state and attempts, in ID order. */
    public List<Map<String, Object>> outbox(String key) {
        return db.rows("select id, channel, kind, state, attempts from outbox where issue_id = ? order by id", id(key));
    }

    /** Inbox rows about the issue (its own items), any account, any kind, in ID order. */
    public List<Map<String, Object>> notifications(String key) {
        return db.rows("select id, account_id, kind, read_at from notification where issue_id = ? order by id",
                id(key));
    }

    /** A time column's value as an instant; null stays null. */
    public static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp t) {
            return t.toInstant();
        }
        if (value instanceof OffsetDateTime o) {
            return o.toInstant();
        }
        throw new AssertionError("Not a time: " + value + " (" + value.getClass().getName() + ")");
    }

    /** The issue's history rows, in ID order: field, old and new value, actor, time. */
    public List<Map<String, Object>> history(String key) {
        return db.rows("select id, kind, field, old_value, new_value, actor_id, created_at from issue_history "
                + "where issue_id = ? order by id", id(key));
    }
}
