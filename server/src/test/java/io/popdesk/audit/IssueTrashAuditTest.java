package io.popdesk.audit;

import static io.popdesk.audit.AuditSteps.canonical;
import static io.popdesk.audit.AuditSteps.entries;
import static io.popdesk.audit.AuditSteps.json;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.support.AuditChainMath;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.trash.TrashSteps;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * The audit entries of the trash (S7A-AC-07, AUDIT-1, acceptance 5절 8): an admin's delete, restore, and purge
 * each leave one entry, {@code ISSUE_DELETED}, {@code ISSUE_RESTORED}, {@code ISSUE_PURGED}, whose target is the
 * company with the issue key, {@code {"type": "COMPANY", "code": ..., "issueKey": ...}}, and whose changes are
 * null (no value changed, as the contract says). Refused requests leave none. Each test makes its own company, so it reads only its own entries.
 */
class IssueTrashAuditTest extends BareAppTest {

    private static final List<String> ACTIONS = List.of("ISSUE_DELETED", "ISSUE_RESTORED", "ISSUE_PURGED");

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    /** The last step the admin takes; the steps before it are the delete. */
    enum Step {
        DELETE("ISSUE_DELETED"),
        RESTORE("ISSUE_RESTORED"),
        PURGE("ISSUE_PURGED");

        private final String action;

        Step(String action) {
            this.action = action;
        }
    }

    private static JsonNode target(String code, String key) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("type", "COMPANY");
        target.put("code", code);
        target.put("issueKey", key);
        return json(target);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Step.class)
    @DisplayName("S7A-AC-07 an admin's delete, restore, and purge each leave one entry (ISSUE_DELETED, ISSUE_RESTORED, ISSUE_PURGED) by the admin, with the target {type COMPANY, code, issueKey} and changes null; an agent's refused delete and a second delete leave none; the chain is valid")
    void oneEntryPerStep(Step step) {
        Session admin = fixtures.admin(flow.admin());
        String code = fixtures.createCompany(admin).code();
        Session reporter = fixtures.customer(admin, code);
        Session agent = fixtures.agent(admin, code);
        Issue issue = fixtures.fileIssue(reporter, code);
        String key = issue.key();

        assertThat(TrashSteps.deleteRequest(agent, key).send().status()).as("the agent's delete").isEqualTo(403);
        TrashSteps.delete(admin, key);
        assertThat(TrashSteps.deleteRequest(admin, key).send().status()).as("the second delete").isEqualTo(404);
        flow.tick();
        switch (step) {
            case DELETE -> { }
            case RESTORE -> TrashSteps.restore(admin, key);
            case PURGE -> TrashSteps.purge(admin, key);
        }

        List<JsonNode> found = entries(admin, "targetCode", code, "action", ACTIONS.get(0), "action", ACTIONS.get(1),
                "action", ACTIONS.get(2));
        List<String> expected = step == Step.DELETE ? List.of("ISSUE_DELETED")
                : List.of(step.action, "ISSUE_DELETED");
        assertThat(found.stream().map(e -> e.path("action").asString(null)).toList())
                .as("trash entries of the company, newest first").isEqualTo(expected);
        for (JsonNode entry : found) {
            assertThat(canonical(entry.path("target"))).as("target of %s", entry)
                    .isEqualTo(canonical(target(code, key)));
            // changes is required in the contract, with the value null: a missing field fails.
            assertThat(entry.path("changes").isNull()).as("changes of %s is null", entry).isTrue();
            assertThat(entry.path("actor").path("accountId").asLong(-1)).as("actor of %s", entry)
                    .isEqualTo(admin.accountId());
        }
        AuditChainMath.assertValid(admin.client());
    }
}
