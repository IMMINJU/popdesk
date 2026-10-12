package io.popdesk.issue;

import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.DbInspector;
import io.popdesk.support.DbInspector.Column;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Steps shared by the tests of slice 4 (talk): editing and deleting comments, mentions and their
 * candidates, and a person's inbox on either app. Only HTTP, except {@link #tablesContaining}. What
 * {@link IssueFlow} and the fixtures already do (plain comments, statuses, assignees, people) is used
 * from there.
 *
 * <p>Every step expects its one status and fails with an {@link AssertionError} otherwise, so before
 * the slice is built a test fails at the step, on an assertion. A method named {@code ...Request}
 * gives the same request without sending it, for a test that reads another status.
 *
 * <p>All methods are static: the steps need nothing but the caller's session. Public, because tests
 * of other packages use it.
 */
public final class TalkFlow {

    /** A comment ID far above any the app has given out. */
    public static final long NO_COMMENT = 9_000_000_000_000_000L;

    private static final int MAX_PAGES = 50;

    private TalkFlow() {}

    /** A JSON body from names and values (name, value, name, value, ...). A value may be null. */
    public static Map<String, Object> body(Object... pairs) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            body.put((String) pairs[i], pairs[i + 1]);
        }
        return body;
    }

    // Comments

    /** The comments of an issue on the caller's app. */
    public static String commentsPath(Session session, String key) {
        return session.api("/issues/" + key + "/comments");
    }

    /** One comment on the caller's app. {@code id} is a number, or any text for a test about bad IDs. */
    public static String commentPath(Session session, String key, Object id) {
        return commentsPath(session, key) + "/" + id;
    }

    /** {@code POST .../comments} with this body as it is, not sent yet. */
    public static ApiRequest addRequest(Session session, String key, Map<String, Object> body) {
        return session.client().post(commentsPath(session, key)).json(body);
    }

    /** Adds a comment with this body ({@code content}, {@code internal}, {@code mentions}). */
    public static Comment add(Session session, String key, Map<String, Object> body) {
        ApiResponse response = addRequest(session, key, body).expect(201);
        return new Comment(response.jsonLong("id"), key, Boolean.TRUE.equals(body.get("internal")), response.json());
    }

    /** Staff add a comment with a content of its own that mentions these accounts. */
    public static Comment mention(Session staff, String key, boolean internal, Collection<Long> accountIds) {
        return add(staff, key, body("content", Unique.text("Mentioning"), "internal", internal,
                "mentions", List.copyOf(accountIds)));
    }

    /** {@code PATCH .../comments/{id}} with this body as it is, not sent yet. */
    public static ApiRequest editRequest(Session session, String key, long id, Map<String, Object> body) {
        return session.client().patch(commentPath(session, key, id)).json(body);
    }

    /** Edits a comment with this body ({@code content}, on the console also {@code mentions}). */
    public static JsonNode edit(Session session, String key, long id, Map<String, Object> body) {
        return editRequest(session, key, id, body).expect(200).json();
    }

    /** Changes the content of a comment and nothing else. */
    public static JsonNode editContent(Session session, String key, long id, String content) {
        return edit(session, key, id, body("content", content));
    }

    /** Staff replace the mentions of a comment and nothing else. */
    public static JsonNode editMentions(Session staff, String key, long id, Collection<Long> accountIds) {
        return edit(staff, key, id, body("mentions", List.copyOf(accountIds)));
    }

    /** {@code DELETE .../comments/{id}}, not sent yet. */
    public static ApiRequest deleteRequest(Session session, String key, long id) {
        return session.client().delete(commentPath(session, key, id));
    }

    public static void delete(Session session, String key, long id) {
        deleteRequest(session, key, id).expect(204);
    }

    /** The first 100 comments of the issue as the caller sees them; expects 200. */
    public static ApiResponse commentPage(Session session, String key) {
        return session.client().get(commentsPath(session, key)).query("size", 100).expect(200);
    }

    /** The first 100 comments of the issue as the caller sees them, oldest first. */
    public static List<JsonNode> comments(Session session, String key) {
        return IssueSteps.items(commentPage(session, key));
    }

    /** The comment in the caller's list, or null when the caller's list does not have it. */
    public static JsonNode find(Session session, String key, long id) {
        return comments(session, key).stream().filter(c -> c.path("id").asLong(-1) == id).findFirst().orElse(null);
    }

    /** The comment in the caller's list. Fails when it is not there. */
    public static JsonNode comment(Session session, String key, long id) {
        JsonNode found = find(session, key, id);
        if (found == null) {
            throw new AssertionError("Comment " + id + " is not in the comment list of " + key + " for "
                    + session.role());
        }
        return found;
    }

    // People in a response (mentions, candidates)

    /** {@code accountId} of each person in an array of people, in its order. Fails when it is no array. */
    public static List<Long> accountIds(JsonNode people) {
        if (!people.isArray()) {
            throw new AssertionError("Not an array of people: " + people);
        }
        return people.values().stream().map(p -> p.path("accountId").asLong(-1)).toList();
    }

    /** The person with this account ID in an array of people, or null. */
    public static JsonNode person(JsonNode people, long accountId) {
        for (JsonNode p : people.values()) {
            if (p.path("accountId").asLong(-1) == accountId) {
                return p;
            }
        }
        return null;
    }

    // Mention candidates

    /** {@code GET /api/console/issues/{key}/mention-candidates?internal=...}, not sent yet. */
    public static ApiRequest candidatesRequest(Session staff, String key, boolean internal) {
        return staff.client().get("/api/console/issues/" + key + "/mention-candidates").query("internal", internal);
    }

    /** The people staff can mention in a public or an internal comment on the issue: the {@code items} array. */
    public static JsonNode candidates(Session staff, String key, boolean internal) {
        ApiResponse response = candidatesRequest(staff, key, internal).expect(200);
        JsonNode items = response.json().path("items");
        if (!items.isArray()) {
            throw new AssertionError(response.request() + ": no items array in " + response.snippet());
        }
        return items;
    }

    // Inbox

    /** Every item of the caller's inbox on the caller's app, newest first, all pages. */
    public static List<JsonNode> inbox(Session session) {
        List<JsonNode> out = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            ApiResponse response = session.client().get(session.api("/notifications")).query("page", page)
                    .query("size", 100).expect(200);
            out.addAll(IssueSteps.items(response));
            if (page >= response.json().path("totalPages").asInt(0)) {
                return out;
            }
        }
        throw new AssertionError("The inbox of " + session.email() + " has more than " + MAX_PAGES + " pages");
    }

    /** The caller's inbox items about one issue, newest first. */
    public static List<JsonNode> inboxOf(Session session, String issueKey) {
        return inbox(session).stream().filter(n -> issueKey.equals(IssueSteps.text(n.path("issue"), "key"))).toList();
    }

    /** The caller's unread count. */
    public static long unreadCount(Session session) {
        return session.client().get(session.api("/notifications/unread-count")).expect(200).jsonLong("count");
    }

    /** {@code POST .../notifications/{id}/read}, not sent yet. */
    public static ApiRequest markReadRequest(Session session, long id) {
        return session.client().post(session.api("/notifications/" + id + "/read"));
    }

    public static void markRead(Session session, long id) {
        markReadRequest(session, id).expect(204);
    }

    public static void markAllRead(Session session) {
        session.client().post(session.api("/notifications/read-all")).expect(204);
    }

    // Searching

    /**
     * JSON paths where {@code number} stands as a whole number or as a text of its digits, leaving out
     * the fields named in {@code skip} (a comment's own {@code id}, the numbers of a page).
     */
    public static List<String> placesOfNumber(JsonNode json, long number, Set<String> skip) {
        List<String> out = new ArrayList<>();
        walk(json, "$", "", number, skip, out);
        return out;
    }

    private static void walk(JsonNode node, String path, String name, long number, Set<String> skip,
            List<String> out) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                walk(e.getValue(), path + "." + e.getKey(), e.getKey(), number, skip, out);
            }
        } else if (node.isArray()) {
            int i = 0;
            for (JsonNode child : node.values()) {
                walk(child, path + "[" + i++ + "]", name, number, skip, out);
            }
        } else if (!skip.contains(name) && ((node.isIntegralNumber() && node.asLong() == number)
                || (node.isString() && node.stringValue().strip().equals(Long.toString(number))))) {
            out.add(path);
        }
    }

    /**
     * Every {@code table.column} of the app's schema that holds {@code needle} in some row, ignoring
     * case: where the app stored a text, whatever the table is called. Text and JSON columns are
     * compared as text, {@code bytea} columns as the UTF-8 bytes of {@code needle} as it is written.
     */
    public static List<String> tablesContaining(DbInspector db, String needle) {
        List<String> out = new ArrayList<>();
        for (String table : db.tables()) {
            for (Column column : db.columns(table)) {
                String col = quote(column.name());
                String where = column.dataType().equals("bytea")
                        ? "position(convert_to(?, 'UTF8') in " + col + ") > 0"
                        : "position(lower(?) in lower(" + col + "::text)) > 0";
                if (db.count("select count(*) from " + quote(table) + " where " + where, needle) > 0) {
                    out.add(table + "." + column.name());
                }
            }
        }
        return out;
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
