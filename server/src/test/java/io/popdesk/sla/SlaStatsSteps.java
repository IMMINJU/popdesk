package io.popdesk.sla;

import io.popdesk.stats.StatsSteps;
import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Session;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Reading the statistics of slice 8b, {@code GET /api/console/stats?days=}, and the {@code sla} of an item (S8B-AC-21
 * to S8B-AC-25). Built on {@link StatsSteps}; every step expects 200 and fails with an {@link AssertionError}
 * otherwise.
 *
 * <p>Public, with public methods, so that the hidden E2E checks can use it.
 */
public final class SlaStatsSteps {

    /** The goal stat of one goal: {@code medianMinutes} (null when nothing was counted), {@code met}, {@code missed}. */
    public record GoalStat(Long medianMinutes, long met, long missed) {}

    private SlaStatsSteps() {}

    /** {@code GET /api/console/stats}, with {@code days} when it is not null; not sent yet. */
    public static ApiRequest request(Session staff, Object days) {
        ApiRequest request = staff.client().get(StatsSteps.STATS);
        return days == null ? request : request.query("days", days);
    }

    /** Every item for {@code days} (null: none given); expects 200. */
    public static List<JsonNode> items(Session staff, Integer days) {
        ApiResponse response = request(staff, days).expect(200);
        JsonNode items = response.json().path("items");
        if (!items.isArray()) {
            throw new AssertionError("No items array in " + response.snippet());
        }
        return items.values().stream().toList();
    }

    /** The item of the company for {@code days}; fails when the answer has none. */
    public static JsonNode itemOf(Session staff, Integer days, String code) {
        return items(staff, days).stream().filter(n -> code.equals(StatsSteps.code(n))).findFirst()
                .orElseThrow(() -> new AssertionError("No statistics item for company " + code + " with days "
                        + days));
    }

    /** {@code sla} of an item, a JSON null when the policy is off. Fails when the field is missing. */
    public static JsonNode sla(JsonNode item) {
        if (!item.has("sla")) {
            throw new AssertionError("No field sla in " + item);
        }
        return item.get("sla");
    }

    /** One goal of the {@code sla} of an item: {@link SlaSteps#FIRST_RESPONSE} or {@link SlaSteps#RESOLUTION}. */
    public static GoalStat goal(JsonNode item, String name) {
        JsonNode goal = sla(item).path(name);
        if (!goal.isObject()) {
            throw new AssertionError("No goal " + name + " in the sla of " + item);
        }
        JsonNode median = goal.path("medianMinutes");
        if (!median.isNull() && !median.isIntegralNumber()) {
            throw new AssertionError("No medianMinutes in " + name + " of " + item);
        }
        return new GoalStat(median.isNull() ? null : median.asLong(), whole(goal, "met", item),
                whole(goal, "missed", item));
    }

    private static long whole(JsonNode goal, String field, JsonNode item) {
        JsonNode value = goal.path(field);
        if (!value.isIntegralNumber()) {
            throw new AssertionError("No whole number in " + field + " of " + item);
        }
        return value.asLong();
    }
}
