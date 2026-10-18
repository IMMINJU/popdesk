package io.popdesk.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Session;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Steps of the account export (S10A-AC-01 to S10A-AC-07, {@code design.md} 3절): the request, the file read as
 * JSON, and its parts found by the values a test made itself. The file names no comment or attachment by ID, so a
 * test finds them by a content or a file name no other test has.
 *
 * <p>Public, with public methods, because the log test of the {@code ops} package uses it.
 */
public final class ExportSteps {

    /** The fields of the file, in the order {@code design.md} 3절 writes them. */
    public static final List<String> FILE_FIELDS = List.of("exportedAt", "account", "photo", "issues", "comments",
            "attachments");

    public static final List<String> ACCOUNT_FIELDS = List.of("id", "email", "name", "role", "status", "locale",
            "createdAt", "anonymized");

    /** The contract names the public flag {@code private} and the size {@code size} (C-4 report 6절). */
    public static final List<String> ISSUE_FIELDS = List.of("key", "companyCode", "title", "content", "status",
            "private", "createdAt", "closedAt", "deletedAt");

    public static final List<String> COMMENT_FIELDS = List.of("issueKey", "content", "internal", "createdAt",
            "editedAt");

    public static final List<String> ATTACHMENT_FIELDS = List.of("issueKey", "commentId", "fileName", "size",
            "createdAt");

    /** Anything shaped like an email address. */
    public static final Pattern EMAIL_LIKE = Pattern.compile("[^\\s\"'<>@,;:()\\[\\]\\\\]+@[^\\s\"'<>@,;:()\\[\\]\\\\]+");

    private ExportSteps() {}

    public static String path(long accountId) {
        return "/api/console/accounts/" + accountId + "/export";
    }

    /** {@code GET .../export}, not sent yet. */
    public static ApiRequest request(Session caller, long accountId) {
        return caller.client().get(path(accountId));
    }

    /** {@code HEAD .../export}, not sent yet. */
    public static ApiRequest headRequest(Session caller, long accountId) {
        return caller.client().head(path(accountId));
    }

    /** The admin exports the account; expects 200 and gives the answer. */
    public static ApiResponse export(Session admin, long accountId) {
        return request(admin, accountId).expect(200);
    }

    /** The file of the account, read as JSON. */
    public static JsonNode file(Session admin, long accountId) {
        return export(admin, accountId).json();
    }

    /** The field names of an object, in the order the file has them. */
    public static List<String> fieldNames(JsonNode object) {
        assertThat(object != null && object.isObject()).as("an object: %s", object).isTrue();
        return new ArrayList<>(object.propertyNames());
    }

    /** The entries of one of the file's arrays. */
    public static List<JsonNode> list(JsonNode file, String array) {
        JsonNode node = file.path(array);
        assertThat(node.isArray()).as("%s is an array in %s", array, file).isTrue();
        List<JsonNode> out = new ArrayList<>();
        node.forEach(out::add);
        return out;
    }

    /** The issue of the file with this key; fails unless there is one. */
    public static JsonNode issue(JsonNode file, String key) {
        return only(list(file, "issues"), "key", key);
    }

    /** The comment of the file with this content; fails unless there is one. */
    public static JsonNode comment(JsonNode file, String content) {
        return only(list(file, "comments"), "content", content);
    }

    /** The attachment of the file with this file name; fails unless there is one. */
    public static JsonNode attachment(JsonNode file, String fileName) {
        return only(list(file, "attachments"), "fileName", fileName);
    }

    public static List<String> keys(JsonNode file) {
        return list(file, "issues").stream().map(i -> i.path("key").asString("")).toList();
    }

    public static List<String> contents(JsonNode file) {
        return list(file, "comments").stream().map(c -> c.path("content").asString("")).toList();
    }

    public static List<String> fileNames(JsonNode file) {
        return list(file, "attachments").stream().map(a -> a.path("fileName").asString("")).toList();
    }

    private static JsonNode only(List<JsonNode> entries, String field, String value) {
        List<JsonNode> found = entries.stream().filter(e -> value.equals(e.path(field).asString(null))).toList();
        assertThat(found).as("entries with %s %s", field, value).hasSize(1);
        return found.getFirst();
    }

    /** Every text in the body shaped like an email address. */
    public static List<String> emailsIn(String body) {
        List<String> out = new ArrayList<>();
        Matcher m = EMAIL_LIKE.matcher(body);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /** A time field as an instant, or null when it is JSON null. Fails when it is missing or not a time. */
    public static Instant time(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isNull()) {
            return null;
        }
        assertThat(value.isString()).as("time field %s in %s", field, node).isTrue();
        try {
            return OffsetDateTime.parse(value.stringValue()).toInstant();
        } catch (DateTimeParseException e) {
            throw new AssertionError("Not a time in '" + field + "': " + value.stringValue(), e);
        }
    }
}
