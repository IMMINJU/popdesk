package io.popdesk.stats;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Session;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Reading the statistics of slice 7b, {@code GET /api/console/stats} (STATS-1, STATS-2): one item per customer
 * company the caller sees, with its code, name, status, and five counts. The steps expect 200 and fail with an
 * {@link AssertionError} otherwise, so before the slice is built a test fails at the step.
 *
 * <p>Other tests share the database, so a test compares the items of the companies it made, not the whole list.
 *
 * <p>Public, with public methods, so that other packages (the hidden E2E checks among them) can use it.
 */
public final class StatsSteps {

    public static final String STATS = "/api/console/stats";

    /** The fields of an item, as the contract names them ({@code sla} from slice 8b on). */
    public static final List<String> FIELDS = List.of("companyCode", "companyName", "companyStatus", "total", "open",
            "inProgress", "resolved", "closed", "sla");

    private StatsSteps() {}

    /** The whole answer; expects 200. */
    public static ApiResponse read(Session staff) {
        return staff.client().get(STATS).expect(200);
    }

    /** Every item, in the order of the answer. */
    public static List<JsonNode> items(Session staff) {
        ApiResponse response = read(staff);
        JsonNode items = response.json().path("items");
        if (!items.isArray()) {
            throw new AssertionError("No items array in " + response.snippet());
        }
        return items.values().stream().toList();
    }

    /** The company codes of the items, in the order of the answer. */
    public static List<String> codes(Session staff) {
        return codes(items(staff));
    }

    public static List<String> codes(List<JsonNode> items) {
        return items.stream().map(StatsSteps::code).toList();
    }

    /** The item of the company, or null when the answer has none. */
    public static JsonNode item(Session staff, String code) {
        return items(staff).stream().filter(n -> code.equals(code(n))).findFirst().orElse(null);
    }

    /** The item of the company; fails when the answer has none. */
    public static JsonNode itemOf(Session staff, String code) {
        JsonNode item = item(staff, code);
        if (item == null) {
            throw new AssertionError("No statistics item for company " + code);
        }
        return item;
    }

    public static String code(JsonNode item) {
        return item.path("companyCode").asString(null);
    }

    /** A count of an item; fails when the field is not a whole number. */
    public static long count(JsonNode item, String field) {
        JsonNode value = item.path(field);
        if (!value.isIntegralNumber()) {
            throw new AssertionError("No whole number in " + field + " of " + item);
        }
        return value.asLong();
    }

    /** The five counts of an item: total, open, inProgress, resolved, closed. */
    public static List<Long> counts(JsonNode item) {
        return List.of(count(item, "total"), count(item, "open"), count(item, "inProgress"),
                count(item, "resolved"), count(item, "closed"));
    }
}
