package io.popdesk.label;

import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Steps of slice 6a for labels, through the console API only: a company's labels (list, create, edit,
 * delete, admins for all but the list), labels on an issue (add, remove), and setting the due date of an
 * issue. Every step expects its one status and fails with an {@link AssertionError} otherwise, so before the
 * slice is built a test fails at the step. A method named {@code ...Request} gives the same request without
 * sending it, for a test that reads another status.
 *
 * <p>Public, with public methods, because the boundary tests of the {@code boundary} package and the race
 * test of the {@code issue} package use it.
 */
public final class LabelSteps {

    /** A label ID far above any the app has given out. */
    public static final long NO_LABEL = 9_000_000_000_000_000L;

    /** The gray a label gets when it is created without a color ({@code acceptance.md} S6A-AC-01). */
    public static final String DEFAULT_COLOR = "#808080";

    private LabelSteps() {}

    // Names and colors

    /** A label name no other test has: {@code prefix} and a unique number, at most 30 characters. */
    public static String name(String prefix) {
        String name = prefix + " " + Unique.number();
        if (name.length() > 30) {
            throw new AssertionError("A label name is 30 characters at most: " + name);
        }
        return name;
    }

    /** A color in the form the app stores ({@code #} and six upper-case hex digits), different per call. */
    public static String color() {
        return String.format(Locale.ROOT, "#%06X", (Unique.number() * 2_654_435_761L) & 0xFFFFFF);
    }

    // Paths

    public static String labelsPath(String code) {
        return "/api/console/companies/" + code + "/labels";
    }

    public static String labelPath(String code, long id) {
        return labelsPath(code) + "/" + id;
    }

    public static String issueLabelPath(String key, long id) {
        return "/api/console/issues/" + key + "/labels/" + id;
    }

    public static String dueDatePath(String key) {
        return "/api/console/issues/" + key + "/due-date";
    }

    // A company's labels

    /** {@code GET .../companies/{code}/labels}, not sent yet. */
    public static ApiRequest listRequest(Session staff, String code) {
        return staff.client().get(labelsPath(code));
    }

    /** The company's labels as {@code staff} sees them, in the order of the answer. */
    public static List<JsonNode> list(Session staff, String code) {
        return items(listRequest(staff, code).expect(200));
    }

    /** The label of the company with this ID, as the list gives it, or null. */
    public static JsonNode find(Session staff, String code, long id) {
        return list(staff, code).stream().filter(l -> id(l) == id).findFirst().orElse(null);
    }

    /** The body of a create request: the name, and the color when {@code color} is not {@link Body#MISSING}. */
    public static Map<String, Object> body(String name, Object color) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        if (color != Body.MISSING) {
            body.put("color", color);
        }
        return body;
    }

    /** Marks a field a request leaves out. */
    public enum Body {
        MISSING
    }

    /** {@code POST .../companies/{code}/labels} with this body as it is, not sent yet. */
    public static ApiRequest createRequest(Session admin, String code, Map<String, Object> body) {
        return admin.client().post(labelsPath(code)).json(body);
    }

    /** The admin creates a label; expects 201 and gives the label. */
    public static JsonNode create(Session admin, String code, String name, String color) {
        return createRequest(admin, code, body(name, color)).expect(201).json();
    }

    /** The admin creates a label with a new unique name and color. */
    public static JsonNode create(Session admin, String code) {
        return create(admin, code, name("Label"), color());
    }

    /** {@code PATCH .../labels/{id}} with this body as it is, not sent yet. */
    public static ApiRequest updateRequest(Session admin, String code, long id, Map<String, Object> body) {
        return admin.client().patch(labelPath(code, id)).json(body);
    }

    /** The admin changes the given fields ({@code name}, {@code color}); expects 200 and gives the label. */
    public static JsonNode update(Session admin, String code, long id, Map<String, Object> changes) {
        return updateRequest(admin, code, id, changes).expect(200).json();
    }

    public static ApiRequest deleteRequest(Session admin, String code, long id) {
        return admin.client().delete(labelPath(code, id));
    }

    /** The admin deletes a label; expects 200. */
    public static void delete(Session admin, String code, long id) {
        deleteRequest(admin, code, id).expect(200);
    }

    // Labels on an issue

    /** {@code PUT .../issues/{key}/labels/{id}}, not sent yet. */
    public static ApiRequest addRequest(Session staff, String key, long id) {
        return staff.client().put(issueLabelPath(key, id));
    }

    /** Staff adds a label to an issue; expects 200 and gives the issue. */
    public static JsonNode add(Session staff, String key, long id) {
        return addRequest(staff, key, id).expect(200).json();
    }

    /** {@code DELETE .../issues/{key}/labels/{id}}, not sent yet. */
    public static ApiRequest removeRequest(Session staff, String key, long id) {
        return staff.client().delete(issueLabelPath(key, id));
    }

    /** Staff removes a label from an issue; expects 200 and gives the issue. */
    public static JsonNode remove(Session staff, String key, long id) {
        return removeRequest(staff, key, id).expect(200).json();
    }

    // Due date

    public static ApiRequest setDueDateRequest(Session staff, String key, String dueDate) {
        return staff.client().put(dueDatePath(key)).json(Map.of("dueDate", dueDate));
    }

    /** Staff sets the due date of an issue; expects 200 and gives the issue. */
    public static JsonNode setDueDate(Session staff, String key, LocalDate dueDate) {
        return setDueDateRequest(staff, key, dueDate.toString()).expect(200).json();
    }

    public static ApiRequest clearDueDateRequest(Session staff, String key) {
        return staff.client().delete(dueDatePath(key));
    }

    // JSON

    /** {@code id} of a label or a label reference; fails when it is missing. */
    public static long id(JsonNode label) {
        JsonNode id = label.path("id");
        if (!id.isIntegralNumber()) {
            throw new AssertionError("No integer id in label " + label);
        }
        return id.asLong();
    }

    /** The {@code labels} array of a console issue or list item; fails when it is missing. */
    public static List<JsonNode> labels(JsonNode issue) {
        JsonNode labels = issue.path("labels");
        if (!labels.isArray()) {
            throw new AssertionError("No labels array in issue " + issue);
        }
        return new ArrayList<>(labels.values());
    }

    /** {@code name} of each label of a console issue, in the order given. */
    public static List<String> labelNames(JsonNode issue) {
        return labels(issue).stream().map(l -> l.path("name").asString(null)).toList();
    }

    /** {@code id} of each label of a console issue, in the order given. */
    public static List<Long> labelIds(JsonNode issue) {
        return labels(issue).stream().map(LabelSteps::id).toList();
    }

    /** Fails unless the answer has this status and this error {@code code}. */
    public static void assertError(ApiResponse response, int status, String code) {
        if (response.status() != status || !code.equals(response.errorCode())) {
            throw new AssertionError("Expected " + status + " " + code + " from " + response.request() + " but got "
                    + response.status() + ": " + response.snippet());
        }
    }

    /** {@code field} of each entry of {@code fields} in a 400 body. */
    public static List<String> fieldNames(ApiResponse error) {
        JsonNode fields = error.json().path("fields");
        if (!fields.isArray()) {
            throw new AssertionError(error.request() + ": no fields array in " + error.snippet());
        }
        return fields.values().stream().map(f -> f.path("field").asString(null)).toList();
    }

    /** {@code items} of an answer; fails when it is missing. */
    public static List<JsonNode> items(ApiResponse response) {
        JsonNode items = response.json().path("items");
        if (!items.isArray()) {
            throw new AssertionError(response.request() + ": no items array in " + response.snippet());
        }
        return new ArrayList<>(items.values());
    }
}
