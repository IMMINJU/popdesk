package io.popdesk.branding;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.AuditChainMath;
import io.popdesk.support.Fixtures.Session;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The audit entry of a branding change (S9-AC-20, AUDIT-1): one {@code BRANDING_CHANGED} per save that changed the
 * display name or the accent color, with the target {@code {"type": "BRANDING"}} and, in {@code changes}, only the
 * changed fields as {@code {from, to}}; an accent color is its hex in capitals or {@code null}. The test reads the
 * entries of an admin it made itself ({@code actorAccountId}).
 */
class BrandingAuditTest extends BrandingAppTest {

    private static final String LOG = "/api/console/audit-log";

    private static List<JsonNode> entries(Session admin) {
        ApiResponse page = admin.client().get(LOG).query("actorAccountId", admin.accountId())
                .query("action", "BRANDING_CHANGED").query("size", 100).expect(200);
        JsonNode items = page.json().path("items");
        if (!items.isArray()) {
            throw new AssertionError(page.request() + ": no items array in " + page.snippet());
        }
        return new ArrayList<>(items.values());
    }

    private static Map<String, Object> fromTo(Object from, Object to) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("from", from);
        change.put("to", to);
        return change;
    }

    private static Map<String, Object> changes(String field, Object from, Object to) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(field, fromTo(from, to));
        return out;
    }

    @Test
    @DisplayName("S9-AC-20 each save that changes the display name or the accent color leaves one BRANDING_CHANGED "
            + "entry by the admin, target {type BRANDING}, changes holding only the changed fields as {from, to}, the "
            + "accent in capitals or null; the same values again and a refused save leave none")
    void oneEntryPerChange() {
        Session first = fixtures.signInAdmin();
        Session admin = fixtures.admin(first);
        String startName = uniqueName();
        String newName = uniqueName();
        String lastName = uniqueName();
        JsonNode start = save(first, startName, EMERALD);

        JsonNode named = update(admin, body(version(start), newName, EMERALD)).expect(200).json();
        JsonNode colored = update(admin, body(version(named), newName, "#6d28d9")).expect(200).json();
        JsonNode both = update(admin, body(version(colored), lastName, null)).expect(200).json();
        update(admin, body(version(both), lastName, null)).expect(200);
        update(admin, body(version(both), lastName, "#FACC15")).expect(400);

        List<String> found = entries(admin).stream().map(e -> {
            assertThat(canonical(e.path("target"))).as("target of %s", e)
                    .isEqualTo(canonical(json(Map.of("type", "BRANDING"))));
            assertThat(e.path("actor").path("accountId").asLong(-1)).as("actor of %s", e)
                    .isEqualTo(admin.accountId());
            return canonical(e.path("changes"));
        }).toList();
        Map<String, Object> lastChanges = new LinkedHashMap<>();
        lastChanges.put("displayName", fromTo(newName, lastName));
        lastChanges.put("accent", fromTo("#6D28D9", null));
        assertThat(found).as("changes of the BRANDING_CHANGED entries of the admin").containsExactlyInAnyOrder(
                canonical(json(changes("displayName", startName, newName))),
                canonical(json(changes("accent", EMERALD, "#6D28D9"))),
                canonical(json(lastChanges)));
        AuditChainMath.assertValid(admin.client());
    }
}
