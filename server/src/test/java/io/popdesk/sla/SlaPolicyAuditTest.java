package io.popdesk.sla;

import static io.popdesk.sla.SlaSteps.canonical;
import static io.popdesk.sla.SlaSteps.changeRequest;
import static io.popdesk.sla.SlaSteps.json;
import static io.popdesk.sla.SlaSteps.policy;
import static io.popdesk.sla.SlaSteps.version;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.sla.SlaSteps.Policy;
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
 * The audit entry of a policy change (S8A-AC-06, AUDIT-1): one {@code SLA_POLICY_CHANGED} per new version, with the
 * target {@code {"type": "SLA_POLICY", "version": n}} and, in {@code changes}, each changed part of {@code targets},
 * {@code hours}, {@code holidays} as {@code {from, to}} with the whole lists in the shapes of the policy response
 * (conventions.md, audit). The test reads the entries of an admin it made itself ({@code actorAccountId}).
 */
class SlaPolicyAuditTest extends SlaAppTest {

    private static final String LOG = "/api/console/audit-log";

    private static List<JsonNode> entries(Session admin, long actor) {
        ApiResponse page = admin.client().get(LOG).query("actorAccountId", actor)
                .query("action", "SLA_POLICY_CHANGED").query("size", 100).expect(200);
        JsonNode items = page.json().path("items");
        if (!items.isArray()) {
            throw new AssertionError(page.request() + ": no items array in " + page.snippet());
        }
        return new ArrayList<>(items.values());
    }

    private static Map<String, Object> fromTo(JsonNode from, Object to) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("from", from);
        change.put("to", to);
        return change;
    }

    @Test
    @DisplayName("S8A-AC-06 a change that makes a new version leaves one SLA_POLICY_CHANGED entry by the admin, with "
            + "the target {type SLA_POLICY, version} of the new version and changes holding only the changed parts, "
            + "each from the lists before to the lists after; the same values sent again and a refused change "
            + "leave none")
    void oneEntryPerNewVersion() {
        Session first = fixtures.signInAdmin();
        Session admin = fixtures.admin(first);
        Policy start = SlaPolicyTest.somePolicy();
        JsonNode before = SlaSteps.change(first, start);
        // The same business hours; another goal for NORMAL, and another unique holiday.
        Policy changed = SlaPolicyTest.somePolicy().target("NORMAL", 30, 480);

        JsonNode after = changeRequest(admin, changed.body(version(before))).expect(200).json();
        changeRequest(admin, changed.body(version(after))).expect(200);
        Map<String, Object> wrong = changed.body(version(after));
        wrong.remove("version");
        changeRequest(admin, wrong).expect(400);

        assertThat(version(after)).as("version of the change").isEqualTo(version(before) + 1);
        List<JsonNode> found = entries(admin, admin.accountId());
        assertThat(found).as("SLA_POLICY_CHANGED entries of the admin").hasSize(1);
        JsonNode entry = found.getFirst();
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("type", "SLA_POLICY");
        target.put("version", version(after));
        assertThat(canonical(entry.path("target"))).as("target of %s", entry).isEqualTo(canonical(json(target)));
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("targets", fromTo(before.path("targets"), changed.targets()));
        changes.put("holidays", fromTo(before.path("holidays"), changed.holidays()));
        assertThat(canonical(entry.path("changes"))).as("changes of %s", entry).isEqualTo(canonical(json(changes)));
        assertThat(entry.path("actor").path("accountId").asLong(-1)).as("actor of %s", entry)
                .isEqualTo(admin.accountId());
        assertThat(version(policy(admin))).as("version after the same values and the refusal")
                .isEqualTo(version(after));
        AuditChainMath.assertValid(admin.client());
    }
}
