package io.popdesk.issue;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The customer's issue and comment types in the committed contract file
 * {@code docs/integrate/openapi-portal.json}. No app is started: the file is generated from the code
 * and CI checks that it matches (S0-AC-91), so this snapshot holds the code to it.
 */
class PortalIssueContractTest {

    /** Name parts of staff-only fields: assignee, labels, due date, history, first response, created by, internal. */
    static final List<String> STAFF_ONLY = List.of("assignee", "label", "due", "history", "firstresponse",
            "createdby", "creator", "internal");

    /**
     * The customer's issue and comment types and their fields, as fixed for this slice.
     * {@code Person.photoUpdatedAt} came with slice 1a (S1A-AC-25, S1A-AC-26): the time a customer's
     * photo last changed, never a staff member's. Slice 3 added {@code filedBySupport} (a flag, never
     * the agent who filed: S3-AC-36), {@code resolvedAt} and {@code closedAt}, and on the issue
     * {@code reopenable} (S3-AC-59).
     */
    private static final Map<String, List<String>> SNAPSHOT = new TreeMap<>(Map.of(
            "Comment", List.of("author", "content", "createdAt", "id"),
            "Issue", List.of("closedAt", "companyCode", "companyName", "content", "createdAt", "filedBySupport",
                    "key", "priority", "private", "reopenable", "reporter", "resolvedAt", "status", "title",
                    "updatedAt", "version"),
            "IssueSummary", List.of("closedAt", "companyCode", "companyName", "createdAt", "filedBySupport", "key",
                    "priority", "private", "reporter", "resolvedAt", "status", "title"),
            "PagedComment", List.of("items", "page", "size", "totalElements", "totalPages"),
            "PagedIssueSummary", List.of("items", "page", "size", "totalElements", "totalPages"),
            "Person", List.of("accountId", "kind", "name", "photoUpdatedAt")));

    private static JsonNode contract() throws IOException {
        Path file = Path.of("..", "docs", "integrate", "openapi-portal.json");
        return JsonMapper.builder().build().readTree(Files.readString(file));
    }

    /** Fields of every schema an issue or comment endpoint of the portal answers with, by schema name. */
    private static Map<String, List<String>> answeredTypes(JsonNode contract) {
        Map<String, List<String>> out = new TreeMap<>();
        JsonNode paths = contract.path("paths");
        for (String path : paths.propertyNames()) {
            if (!path.startsWith("/api/portal/issues") || path.contains("/attachments")) {
                continue;
            }
            for (JsonNode operation : paths.path(path).values()) {
                collect(operation.path("responses"), contract.path("components").path("schemas"), out);
            }
        }
        out.remove("ApiError");
        out.remove("FieldError");
        return out;
    }

    private static void collect(JsonNode node, JsonNode schemas, Map<String, List<String>> out) {
        if (node.isObject()) {
            JsonNode ref = node.path("$ref");
            if (ref.isString()) {
                String name = ref.stringValue().substring(ref.stringValue().lastIndexOf('/') + 1);
                if (!out.containsKey(name)) {
                    JsonNode schema = schemas.path(name);
                    out.put(name, new ArrayList<>(schema.path("properties").propertyNames()).stream().sorted().toList());
                    collect(schema, schemas, out);
                }
            }
            for (JsonNode child : node.values()) {
                collect(child, schemas, out);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node.values()) {
                collect(child, schemas, out);
            }
        }
    }

    @Test
    @DisplayName("S0-AC-49 the portal's issue and comment types have no staff-only fields")
    void noStaffOnlyFields() throws IOException {
        Map<String, List<String>> types = answeredTypes(contract());

        assertThat(types).containsKeys("Issue", "IssueSummary", "Comment");
        types.forEach((type, fields) -> assertThat(fields)
                .as("fields of %s", type)
                .noneMatch(f -> STAFF_ONLY.stream().anyMatch(f.toLowerCase(Locale.ROOT)::contains)));
    }

    @Test
    @DisplayName("S0-AC-49 the portal's issue and comment types match the snapshot")
    void typesMatchSnapshot() throws IOException {
        assertThat(answeredTypes(contract())).isEqualTo(SNAPSHOT);
    }
}
