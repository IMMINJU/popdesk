package io.popdesk.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The customer's inbox types in the committed contract file {@code docs/integrate/openapi-portal.json},
 * read the way {@code issue.PortalIssueContractTest} reads the issue types. No app is started: the file
 * is generated from the code and CI checks that it matches, so this snapshot holds the code to it.
 *
 * <p>The portal's item says who did something only as a customer or the support team, and its kinds
 * are the six a customer can get: nothing of internal comments, assignees, operational alerts, or
 * membership requests ({@code design.md} 4절).
 */
class PortalNotificationContractTest {

    /** The types the portal's inbox endpoints answer with, and their fields. */
    private static final Map<String, List<String>> SNAPSHOT = new TreeMap<>(Map.of(
            "Notification", List.of("actor", "commentId", "companyCode", "createdAt", "id", "issue", "kind",
                    "readAt"),
            "NotificationActor", List.of("accountId", "kind", "name", "photoUpdatedAt"),
            "NotificationIssue", List.of("key", "title"),
            "PagedNotification", List.of("items", "page", "size", "totalElements", "totalPages"),
            "UnreadCount", List.of("count")));

    /** The kinds of item a customer can get. */
    private static final List<String> KINDS = List.of("STAFF_COMMENT", "ISSUE_RESOLVED", "ISSUE_CLOSED",
            "ISSUE_REOPENED", "ISSUE_FILED_ON_BEHALF", "MENTIONED");

    private static JsonNode contract() throws IOException {
        Path file = Path.of("..", "docs", "integrate", "openapi-portal.json");
        return JsonMapper.builder().build().readTree(Files.readString(file));
    }

    /** Fields of every schema an inbox endpoint of the portal answers with, by schema name. */
    private static Map<String, List<String>> answeredTypes(JsonNode contract) {
        Map<String, List<String>> out = new TreeMap<>();
        JsonNode paths = contract.path("paths");
        for (String path : paths.propertyNames()) {
            if (path.startsWith("/api/portal/notifications")) {
                for (JsonNode operation : paths.path(path).values()) {
                    collect(operation.path("responses"), contract.path("components").path("schemas"), out);
                }
            }
        }
        out.remove("ApiError");
        out.remove("FieldError");
        return out;
    }

    private static void collect(JsonNode node, JsonNode schemas, Map<String, List<String>> out) {
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
    }

    /** The values of an enum: written in the property, or in the schema the property points to. */
    private static List<String> enumValues(JsonNode property, JsonNode schemas) {
        JsonNode holder = property;
        JsonNode ref = property.path("$ref");
        if (ref.isString()) {
            holder = schemas.path(ref.stringValue().substring(ref.stringValue().lastIndexOf('/') + 1));
        }
        return holder.path("enum").values().stream().map(v -> v.asString(null)).toList();
    }

    @Test
    @DisplayName("S4-AC-46 the portal's inbox types and their fields match the snapshot")
    void typesMatchSnapshot() throws IOException {
        assertThat(answeredTypes(contract())).isEqualTo(SNAPSHOT);
    }

    @Test
    @DisplayName("S4-AC-46 the portal's item kinds are the six a customer can get, and who did it is a customer "
            + "or the support team")
    void kindsAreTheCustomersOnly() throws IOException {
        JsonNode schemas = contract().path("components").path("schemas");

        assertThat(enumValues(schemas.path("Notification").path("properties").path("kind"), schemas))
                .as("kinds of the portal's Notification").containsExactlyInAnyOrderElementsOf(KINDS);
        assertThat(enumValues(schemas.path("NotificationActor").path("properties").path("kind"), schemas))
                .as("kinds of the portal's NotificationActor").containsExactlyInAnyOrder("CUSTOMER", "SUPPORT_TEAM");
    }
}
