package io.popdesk.audit;

import static io.popdesk.audit.AuditSteps.canonical;
import static io.popdesk.audit.AuditSteps.entries;
import static io.popdesk.audit.AuditSteps.json;
import static io.popdesk.audit.AuditSteps.onlyEntry;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.label.LabelSteps;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.AuditChainMath;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * The audit entries of a company's labels (slice 6b, AUDIT-1, S6B-AC-80): {@code LABEL_CREATED},
 * {@code LABEL_UPDATED}, and {@code LABEL_DELETED}, with the company as the target like the other company
 * entries, and what changed. Each test makes its own company, so it reads only entries about labels it made.
 * Label writes are done by a new admin of the test.
 */
class LabelAuditTest extends BareAppTest {

    private static final List<String> LABEL_ACTIONS = List.of("LABEL_CREATED", "LABEL_UPDATED", "LABEL_DELETED");

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    /** The target of the entries about a label: the company, by code, and the label. */
    private static JsonNode target(String code, long labelId) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("type", "COMPANY");
        target.put("code", code);
        target.put("labelId", labelId);
        return json(target);
    }

    /** The company's entries of the three label actions, newest first. */
    private static List<JsonNode> labelEntries(Session admin, String code) {
        return entries(admin, "targetCode", code, "action", LABEL_ACTIONS.get(0), "action", LABEL_ACTIONS.get(1),
                "action", LABEL_ACTIONS.get(2));
    }

    private static void assertEntry(JsonNode entry, Session admin, String code, long labelId, Object changes) {
        assertThat(canonical(entry.path("target"))).as("target of %s", entry)
                .isEqualTo(canonical(target(code, labelId)));
        assertThat(canonical(entry.path("changes"))).as("changes of %s", entry).isEqualTo(canonical(json(changes)));
        assertThat(entry.path("actor").path("accountId").asLong(-1)).as("actor of %s", entry)
                .isEqualTo(admin.accountId());
    }

    @Test
    @DisplayName("S6B-AC-80 creating a label leaves one LABEL_CREATED entry with the name and color as they are, "
            + "deleting one LABEL_DELETED with the deleted label's name and color, both with the target "
            + "{type COMPANY, code, labelId}; the chain is valid")
    void createdAndDeletedEntries() {
        Session admin = fixtures.admin(flow.admin());
        String code = fixtures.createCompany(admin).code();
        String name = LabelSteps.name("Audited");
        String color = LabelSteps.color();

        long id = LabelSteps.id(LabelSteps.create(admin, code, name, color));
        JsonNode created = onlyEntry(admin, "targetCode", code, "action", "LABEL_CREATED");
        flow.tick();
        LabelSteps.delete(admin, code, id);

        assertEntry(created, admin, code, id, Map.of("name", name, "color", color));
        JsonNode deleted = onlyEntry(admin, "targetCode", code, "action", "LABEL_DELETED");
        assertEntry(deleted, admin, code, id, Map.of("name", name, "color", color));
        assertThat(labelEntries(admin, code)).as("label entries of the company").hasSize(2);
        AuditChainMath.assertValid(admin.client());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"name", "color", "name and color"})
    @DisplayName("S6B-AC-80 editing a label leaves one LABEL_UPDATED entry holding only the changed fields as "
            + "{from, to}; a field sent with its value of now is not among them")
    void updatedEntryHoldsTheChangedFields(String what) {
        Session admin = fixtures.admin(flow.admin());
        String code = fixtures.createCompany(admin).code();
        String name = LabelSteps.name("Before");
        String color = LabelSteps.color();
        long id = LabelSteps.id(LabelSteps.create(admin, code, name, color));
        String newName = what.contains("name") ? LabelSteps.name("After") : name;
        String newColor = what.contains("color") ? LabelSteps.color() : color;

        LabelSteps.update(admin, code, id, Map.of("name", newName, "color", newColor));

        Map<String, Object> changes = new LinkedHashMap<>();
        if (!newName.equals(name)) {
            changes.put("name", Map.of("from", name, "to", newName));
        }
        if (!newColor.equals(color)) {
            changes.put("color", Map.of("from", color, "to", newColor));
        }
        assertEntry(onlyEntry(admin, "targetCode", code, "action", "LABEL_UPDATED"), admin, code, id, changes);
        AuditChainMath.assertValid(admin.client());
    }

    @Test
    @DisplayName("S6B-AC-80 an edit that changes nothing and refused requests (a taken name, a wrong color, an "
            + "agent) leave no entry; the two labels made leave their two")
    void noEntryWithoutAChange() {
        Session admin = fixtures.admin(flow.admin());
        Company company = fixtures.createCompany(admin);
        String code = company.code();
        Session agent = fixtures.agent(admin, code);
        JsonNode first = LabelSteps.create(admin, code);
        JsonNode second = LabelSteps.create(admin, code);
        long id = LabelSteps.id(first);
        String name = first.path("name").asString(null);
        String color = first.path("color").asString(null);

        LabelSteps.update(admin, code, id, Map.of("name", name, "color", color));
        LabelSteps.update(admin, code, id, Map.of("name", name));
        List<ApiResponse> refused = List.of(
                LabelSteps.createRequest(admin, code, LabelSteps.body(name, LabelSteps.color())).send(),
                LabelSteps.updateRequest(admin, code, LabelSteps.id(second), Map.of("name", name)).send(),
                LabelSteps.createRequest(admin, code, LabelSteps.body(LabelSteps.name("Wrong"), "red")).send(),
                LabelSteps.updateRequest(admin, code, id, Map.of("color", "#12345")).send(),
                LabelSteps.createRequest(agent, code, LabelSteps.body(LabelSteps.name("Agent"), null)).send(),
                LabelSteps.updateRequest(agent, code, id, Map.of("name", LabelSteps.name("Agent"))).send(),
                LabelSteps.deleteRequest(agent, code, id).send());

        assertThat(refused).as("the refused requests").allSatisfy(r -> assertThat(r.status())
                .as(r.request() + " -> " + r.snippet()).isBetween(400, 499));
        List<JsonNode> entries = labelEntries(admin, code);
        assertThat(entries.stream().map(e -> e.path("action").asString(null)).toList())
                .as("label entries of the company").containsExactly("LABEL_CREATED", "LABEL_CREATED");
        assertThat(LabelSteps.find(admin, code, id)).as("the first label after it all").isNotNull();
    }

    @Test
    @DisplayName("S6B-AC-80 the label entries are found with the audit log's target type COMPANY and target code "
            + "filters, and not with target type ACCOUNT")
    void entriesAreFoundByTheCompanyFilters() {
        Session admin = fixtures.admin(flow.admin());
        String code = fixtures.createCompany(admin).code();
        long id = LabelSteps.id(LabelSteps.create(admin, code));
        LabelSteps.update(admin, code, id, Map.of("name", LabelSteps.name("Renamed")));
        List<Long> ids = labelEntries(admin, code).stream().map(e -> e.path("id").asLong(-1)).toList();
        assertThat(ids).as("label entries of the company by target code").hasSize(2);

        List<Long> ofCompanies = entries(admin, "targetType", "COMPANY", "targetCode", code).stream()
                .map(e -> e.path("id").asLong(-1)).toList();
        List<Long> ofAccounts = entries(admin, "targetType", "ACCOUNT", "action", "LABEL_CREATED", "action",
                "LABEL_UPDATED").stream().map(e -> e.path("id").asLong(-1)).toList();

        assertThat(ofCompanies).as("entries with target type COMPANY and the code").containsAll(ids);
        assertThat(ofAccounts).as("label entries with target type ACCOUNT").doesNotContainAnyElementsOf(ids);
    }
}
