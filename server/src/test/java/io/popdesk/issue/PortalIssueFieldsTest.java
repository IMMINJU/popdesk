package io.popdesk.issue;

import static io.popdesk.issue.IssueSteps.items;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueSteps.Scene;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** What the app really sends customers about issues and comments, next to the contract snapshot. */
class PortalIssueFieldsTest extends BareAppTest {

    private IssueSteps steps;

    @BeforeEach
    void setUp() {
        steps = new IssueSteps(fixtures, clock);
    }

    /** Every field name in a JSON value, at any depth. */
    private static List<String> fieldNames(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                out.add(e.getKey());
                out.addAll(fieldNames(e.getValue()));
            }
        } else if (node.isArray()) {
            for (JsonNode child : node.values()) {
                out.addAll(fieldNames(child));
            }
        }
        return out;
    }

    @Test
    @DisplayName("S0-AC-49 the portal's issue and comment responses carry no staff-only field, after staff answered")
    void portalResponsesHaveNoStaffOnlyFields() {
        Scene s = steps.scene();
        Session agent = fixtures.agent(s.admin(), s.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        fixtures.comment(agent, issue.key(), "Public answer");
        fixtures.internalComment(agent, issue.key(), "Internal note");

        ApiResponse created = s.customer().client().post("/api/portal/issues/" + issue.key() + "/comments")
                .json(Map.of("content", "Thanks")).expect(201);
        ApiResponse detail = s.customer().client().get("/api/portal/issues/" + issue.key()).expect(200);
        ApiResponse list = s.customer().client().get("/api/portal/issues").expect(200);
        ApiResponse comments = s.customer().client().get("/api/portal/issues/" + issue.key() + "/comments")
                .expect(200);

        Map<String, JsonNode> bodies = Map.of(
                "filing response", issue.json(),
                created.request(), created.json(),
                detail.request(), detail.json(),
                list.request(), list.json(),
                comments.request(), comments.json());
        bodies.forEach((what, body) -> assertThat(fieldNames(body))
                .as("fields of %s", what)
                .noneMatch(f -> PortalIssueContractTest.STAFF_ONLY.stream()
                        .anyMatch(f.toLowerCase(Locale.ROOT)::contains)));
        assertThat(items(comments)).hasSize(2);
        assertThat(detail.json().propertyNames()).containsExactlyInAnyOrder("closedAt", "companyCode", "companyName",
                "content", "createdAt", "filedBySupport", "key", "priority", "private", "reopenable", "reporter",
                "resolvedAt", "status", "title", "updatedAt", "version");
        assertThat(items(list).getFirst().propertyNames()).as("fields of a list item").containsExactlyInAnyOrder(
                "closedAt", "companyCode", "companyName", "createdAt", "filedBySupport", "key", "priority", "private",
                "reporter", "resolvedAt", "status", "title");
        assertThat(items(comments).getFirst().propertyNames())
                .containsExactlyInAnyOrder("author", "content", "createdAt", "id");
    }
}
