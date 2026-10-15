package io.popdesk.task;

import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.TestClock;
import io.popdesk.support.Unique;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Steps of slice 6b for tasks, through the console API only: the tasks of an issue (list, create, edit,
 * delete), the notes of a task (add, edit, delete), and the caller's own tasks ({@code GET /api/console/tasks}).
 * Every step expects its one status and fails with an {@link AssertionError} otherwise, so before the slice is
 * built a test fails at the step. A method named {@code ...Request} gives the same request without sending it,
 * for a test that reads another status.
 *
 * <p>Task writes are limited to 60 per account in 60 seconds (S6B-AC-13), and the test clock stands still, so a
 * test that writes more than that calls {@link #nextWindow(TestClock)} in between.
 *
 * <p>Public, with public methods, because the boundary tests of the {@code boundary} package, the race test of
 * the {@code issue} package, and the notification tests use it.
 */
public final class TaskSteps {

    /** A task or note ID far above any the app has given out. */
    public static final long NO_TASK = 9_000_000_000_000_000L;

    /** How many task writes one account makes in one window (S6B-AC-13). */
    public static final int WRITES_PER_WINDOW = 60;

    private TaskSteps() {}

    // Titles and bodies

    /** A task title no other test has: {@code prefix} and a unique number. */
    public static String title(String prefix) {
        return prefix + " #" + Unique.number() + "#";
    }

    /**
     * A JSON body from name and value pairs, in order. A value may be null, which sends {@code null}; a name
     * left out is not sent.
     */
    public static Map<String, Object> body(Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new AssertionError("A body takes name and value pairs");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            body.put((String) pairs[i], pairs[i + 1]);
        }
        return body;
    }

    /** Moves the app clock past the 60 seconds of the rate limit, so the next writes start a new window. */
    public static void nextWindow(TestClock clock) {
        clock.advance(Duration.ofSeconds(61));
    }

    // Paths

    public static String tasksPath(String key) {
        return "/api/console/issues/" + key + "/tasks";
    }

    public static String taskPath(String key, Object id) {
        return tasksPath(key) + "/" + id;
    }

    public static String notesPath(String key, Object taskId) {
        return taskPath(key, taskId) + "/notes";
    }

    public static String notePath(String key, Object taskId, Object noteId) {
        return notesPath(key, taskId) + "/" + noteId;
    }

    public static final String MY_TASKS = "/api/console/tasks";

    // The tasks of an issue

    /** {@code GET .../issues/{key}/tasks}, not sent yet. */
    public static ApiRequest listRequest(Session staff, String key) {
        return staff.client().get(tasksPath(key));
    }

    /** The answer of the list, {@code {items, openCount}}; expects 200. */
    public static JsonNode list(Session staff, String key) {
        return listRequest(staff, key).expect(200).json();
    }

    /** The tasks of the issue, in the order of the answer. */
    public static List<JsonNode> tasks(Session staff, String key) {
        JsonNode items = list(staff, key).path("items");
        if (!items.isArray()) {
            throw new AssertionError("No items array in the tasks of " + key);
        }
        return new ArrayList<>(items.values());
    }

    /** {@code openCount} of the issue's task list. */
    public static long openCount(Session staff, String key) {
        JsonNode count = list(staff, key).path("openCount");
        if (!count.isIntegralNumber()) {
            throw new AssertionError("No integer openCount in the tasks of " + key);
        }
        return count.asLong();
    }

    /** The task with this ID as the list gives it. Fails when it is not there. */
    public static JsonNode task(Session staff, String key, long id) {
        return tasks(staff, key).stream().filter(t -> id(t) == id).findFirst()
                .orElseThrow(() -> new AssertionError("Task " + id + " is not in the tasks of " + key));
    }

    /** {@code POST .../tasks} with this body as it is, not sent yet. */
    public static ApiRequest createRequest(Session staff, String key, Map<String, Object> body) {
        return staff.client().post(tasksPath(key)).json(body);
    }

    /** Staff creates a task with this body; expects 201 and gives the task. */
    public static JsonNode create(Session staff, String key, Map<String, Object> body) {
        return createRequest(staff, key, body).expect(201).json();
    }

    /** Staff creates a task with only a title; expects 201 and gives the task. */
    public static JsonNode create(Session staff, String key, String title) {
        return create(staff, key, body("title", title));
    }

    /** Staff creates a task with a new unique title. */
    public static JsonNode create(Session staff, String key) {
        return create(staff, key, title("Task"));
    }

    /** {@code PATCH .../tasks/{id}} with this body as it is, not sent yet. */
    public static ApiRequest updateRequest(Session staff, String key, Object id, Map<String, Object> body) {
        return staff.client().patch(taskPath(key, id)).json(body);
    }

    /** Staff changes the fields of {@code body}; expects 200 and gives the task. */
    public static JsonNode update(Session staff, String key, long id, Map<String, Object> body) {
        return updateRequest(staff, key, id, body).expect(200).json();
    }

    /** {@code DELETE .../tasks/{id}}, not sent yet. */
    public static ApiRequest deleteRequest(Session staff, String key, Object id) {
        return staff.client().delete(taskPath(key, id));
    }

    /** Staff deletes a task; expects 204. */
    public static void delete(Session staff, String key, long id) {
        deleteRequest(staff, key, id).expect(204);
    }

    // Notes

    /** {@code POST .../tasks/{id}/notes} with {@code {"content": content}}, not sent yet. */
    public static ApiRequest noteRequest(Session staff, String key, Object taskId, Object content) {
        return staff.client().post(notesPath(key, taskId)).json(body("content", content));
    }

    /** Staff adds a note; expects 201 and gives the note. */
    public static JsonNode note(Session staff, String key, long taskId, String content) {
        return noteRequest(staff, key, taskId, content).expect(201).json();
    }

    /** {@code PATCH .../notes/{noteId}} with {@code {"content": content}}, not sent yet. */
    public static ApiRequest editNoteRequest(Session staff, String key, Object taskId, Object noteId,
            Object content) {
        return staff.client().patch(notePath(key, taskId, noteId)).json(body("content", content));
    }

    /** The author edits a note; expects 200 and gives the note. */
    public static JsonNode editNote(Session staff, String key, long taskId, long noteId, String content) {
        return editNoteRequest(staff, key, taskId, noteId, content).expect(200).json();
    }

    /** {@code DELETE .../notes/{noteId}}, not sent yet. */
    public static ApiRequest deleteNoteRequest(Session staff, String key, Object taskId, Object noteId) {
        return staff.client().delete(notePath(key, taskId, noteId));
    }

    /** Deletes a note; expects 204. */
    public static void deleteNote(Session staff, String key, long taskId, long noteId) {
        deleteNoteRequest(staff, key, taskId, noteId).expect(204);
    }

    // My tasks

    /** {@code GET /api/console/tasks} with these query parameters (name, value, ...), not sent yet. */
    public static ApiRequest myTasksRequest(Session staff, Object... query) {
        ApiRequest request = staff.client().get(MY_TASKS);
        for (int i = 0; i < query.length; i += 2) {
            request.query((String) query[i], query[i + 1]);
        }
        return request;
    }

    /** One page of the caller's tasks narrowed by {@code query}; expects 200. */
    public static ApiResponse myTasks(Session staff, Object... query) {
        return myTasksRequest(staff, query).expect(200);
    }

    // JSON

    /** {@code id} of a task or a note; fails when it is missing. */
    public static long id(JsonNode node) {
        JsonNode id = node.path("id");
        if (!id.isIntegralNumber()) {
            throw new AssertionError("No integer id in " + node);
        }
        return id.asLong();
    }

    /** A text field, or null when it is null or missing. */
    public static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.stringValue() : null;
    }

    /** {@code accountId} of the person in {@code field}, or null when the field is null. Fails when it is missing. */
    public static Long personId(JsonNode node, String field) {
        JsonNode person = node.path(field);
        if (person.isNull()) {
            return null;
        }
        if (!person.path("accountId").isIntegralNumber()) {
            throw new AssertionError("No person (or null) in '" + field + "' of " + node);
        }
        return person.path("accountId").asLong();
    }

    /** The {@code notes} of a task: notes and changes, in the order of the answer. */
    public static List<JsonNode> notes(JsonNode task) {
        JsonNode notes = task.path("notes");
        if (!notes.isArray()) {
            throw new AssertionError("No notes array in task " + task);
        }
        return new ArrayList<>(notes.values());
    }

    /** The entries of a task of one kind, {@code NOTE} or {@code CHANGE}, in order. */
    public static List<JsonNode> notes(JsonNode task, String kind) {
        return notes(task).stream().filter(n -> kind.equals(text(n, "kind"))).toList();
    }

    /** The {@code CHANGE} entries of a task, in order. */
    public static List<JsonNode> changes(JsonNode task) {
        return notes(task, "CHANGE");
    }

    /** The {@code field} of each {@code CHANGE} entry of a task, in order. */
    public static List<String> changedFields(JsonNode task) {
        return changes(task).stream().map(c -> text(c, "field")).toList();
    }

    /** The entry with this ID of a task. Fails when it is not there. */
    public static JsonNode entry(JsonNode task, long noteId) {
        return notes(task).stream().filter(n -> id(n) == noteId).findFirst()
                .orElseThrow(() -> new AssertionError("Entry " + noteId + " is not in task " + task));
    }

    /** Fails unless the answer has this status and this error {@code code}. */
    public static void assertRefused(ApiResponse response, int status, String code) {
        if (response.status() != status || !code.equals(response.errorCode())) {
            throw new AssertionError("Expected " + status + " " + code + " from " + response.request() + " but got "
                    + response.status() + ": " + response.snippet());
        }
    }

    /** The {@code code} that {@code fields} of a 400 body gives for {@code field}, or null. */
    public static String fieldCode(ApiResponse error, String field) {
        for (JsonNode f : error.json().path("fields").values()) {
            if (field.equals(f.path("field").asString(null))) {
                return f.path("code").asString(null);
            }
        }
        return null;
    }

    /** Fails unless the answer is 400 {@code INVALID_INPUT} with this code for this field. */
    public static void assertInvalid(ApiResponse response, String field, String fieldCode) {
        assertRefused(response, 400, "INVALID_INPUT");
        String got = fieldCode(response, field);
        if (!fieldCode.equals(got)) {
            throw new AssertionError("Expected " + field + " " + fieldCode + " from " + response.request()
                    + " but got " + got + ": " + response.snippet());
        }
    }
}
