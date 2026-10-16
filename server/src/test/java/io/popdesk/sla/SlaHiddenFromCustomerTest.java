package io.popdesk.sla;

import static io.popdesk.due.DueSteps.assertRefused;
import static io.popdesk.due.DueSteps.fieldCode;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.sla.SlaSteps.Policy;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Customers never see SLA (S8A-AC-33, TM-63): the portal's issue responses carry no SLA field for an issue under SLA,
 * whose console detail has one, and the portal list takes neither {@code sla} nor {@code sort=slaDue}.
 */
class SlaHiddenFromCustomerTest extends SlaAppTest {

    /** Every field name in a JSON body, at any depth. */
    private static List<String> fieldNames(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                out.add(field.getKey());
                out.addAll(fieldNames(field.getValue()));
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                out.addAll(fieldNames(child));
            }
        }
        return out;
    }

    @Test
    @DisplayName("S8A-AC-33 for an issue under SLA in the breached state, the portal's filing response, detail, "
            + "list, and close response hold no field named like sla, due, or remaining, while the console detail "
            + "gives sla; the portal list answers sla and sort=slaDue with 400 UNKNOWN_PARAMETER")
    void portalShowsNoSla() {
        Session admin = fixtures.signInAdmin();
        String code = fixtures.createCompany(admin).code();
        Session customer = fixtures.customer(admin, code);
        SlaSteps.change(admin, Policy.off().target("NORMAL", 10, 20));
        Issue issue = fixtures.fileIssue(customer, NewIssue.in(code).priority("NORMAL"));
        sla.advanceMinutes(30);
        Session reader = fixtures.signInAgain(customer);

        JsonNode console = SlaSteps.sla(admin, issue.key());
        ApiResponse detail = reader.client().get("/api/portal/issues/" + issue.key()).expect(200);
        ApiResponse list = reader.client().get("/api/portal/issues").query("companyCode", code).expect(200);
        ApiResponse closed = reader.client().post("/api/portal/issues/" + issue.key() + "/close").expect(200);

        assertThat(SlaSteps.state(SlaSteps.goal(console, SlaSteps.FIRST_RESPONSE))).as("the console's sla: %s",
                console).isEqualTo("BREACHED");
        Map<String, JsonNode> bodies = new LinkedHashMap<>();
        bodies.put("filing response", issue.json());
        bodies.put(detail.request(), detail.json());
        bodies.put(list.request(), list.json());
        bodies.put(closed.request(), closed.json());
        bodies.forEach((what, body) -> assertThat(fieldNames(body)).as("fields of %s", what)
                .noneMatch(f -> {
                    String name = f.toLowerCase(Locale.ROOT);
                    return name.contains("sla") || name.contains("due") || name.contains("remaining");
                }));
        for (String[] parameter : new String[][] {{"sla", "breached"}, {"sla", "warning"}, {"sort", "slaDue"}}) {
            ApiResponse rejected = reader.client().get("/api/portal/issues").query(parameter[0], parameter[1]).send();
            assertRefused(rejected, 400, "INVALID_INPUT");
            assertThat(fieldCode(rejected, parameter[0])).as("code of %s in %s", parameter[0], rejected.snippet())
                    .isEqualTo("UNKNOWN_PARAMETER");
        }
    }
}
