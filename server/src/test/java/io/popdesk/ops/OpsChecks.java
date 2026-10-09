package io.popdesk.ops;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/** Small checks the ops tests share: error bodies, lists, timestamps. */
final class OpsChecks {

    private OpsChecks() {}

    /** Status and {@code code} of an error body. */
    static void assertError(ApiResponse response, int status, String code) {
        assertThat(response.status()).as(response.request() + ": " + response.snippet()).isEqualTo(status);
        assertThat(response.errorCode()).as("code of " + response.request()).isEqualTo(code);
    }

    /** The top-level field names of a JSON object, sorted. */
    static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    /** Every item of a COM-4 list, page by page with {@code size=100}. */
    static List<JsonNode> allItems(ApiClient client, String path) {
        List<JsonNode> out = new ArrayList<>();
        for (int page = 1; page <= 1000; page++) {
            ApiResponse response = client.get(path).query("page", page).query("size", 100).expect(200);
            JsonNode body = response.json();
            JsonNode items = body.path("items");
            if (!items.isArray()) {
                throw new AssertionError(response.request() + ": no items array in " + response.snippet());
            }
            items.forEach(out::add);
            if (page >= body.path("totalPages").asInt(0)) {
                return out;
            }
        }
        throw new AssertionError(path + " has more than 1000 pages");
    }

    /** An ISO-8601 timestamp with an offset, as an instant. Fails with an AssertionError otherwise. */
    static Instant instant(JsonNode node, String what) {
        if (!node.isString()) {
            throw new AssertionError(what + " is not a timestamp: " + node);
        }
        try {
            return OffsetDateTime.parse(node.stringValue()).toInstant();
        } catch (DateTimeParseException e) {
            throw new AssertionError(what + " is not ISO-8601 with an offset: " + node, e);
        }
    }
}
