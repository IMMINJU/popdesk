package io.popdesk.ops;

import static io.popdesk.ops.OpsChecks.fieldNames;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The error body (COM-3) and the rule for missing values (D-44). */
class ErrorBodyTest extends BareAppTest {

    /** What a body must not show: exception names, stack frames, framework packages, SQL (upper case). */
    private static final Pattern LEAK = Pattern.compile(
            "Exception|Throwable|\\bat [a-z]+\\.[a-z]|org\\.springframework|org\\.hibernate|org\\.postgresql"
                    + "|java\\.lang|jakarta\\.|SQLState|\\bSELECT\\b|\\bINSERT\\b|\\bUPDATE\\b|\\bDELETE FROM\\b"
                    + "|violates|ERROR:|\"timestamp\"|\"trace\"|\"path\"");

    private static void assertErrorBody(SoftAssertions soft, ApiResponse response) {
        String what = response.request() + " -> " + response.status() + ": " + response.snippet();
        JsonNode body = response.json();
        soft.assertThat(body.isObject()).as("object body of %s", what).isTrue();
        soft.assertThat(fieldNames(body)).as("fields of %s", what).isSubsetOf(Set.of("code", "message", "fields"))
                .contains("code", "message");
        soft.assertThat(body.path("code").isString()).as("code of %s", what).isTrue();
        soft.assertThat(body.path("message").isString()).as("message of %s", what).isTrue();
        soft.assertThat(LEAK.matcher(response.text()).find()).as("exception, stack, or SQL in %s", what).isFalse();
    }

    @Test
    @DisplayName("S0-AC-85 error bodies are {code, message, fields?} with no exception names, stack, or SQL")
    void errorBodies() {
        Session admin = fixtures.signInAdmin();
        Session agent = fixtures.agent(admin);
        String taken = fixtures.createCompany(admin).code();
        String takenEmail = fixtures.invite(admin, Role.AGENT).email();
        ApiClient anonymous = console();

        List<ApiResponse> errors = new ArrayList<>();
        errors.add(anonymous.get("/api/console/me").send());
        errors.add(agent.client().get("/api/console/system/status").send());
        errors.add(admin.client().get("/api/console/issues/NOPE-999999").send());
        errors.add(admin.client().get("/api/console/companies").query("size", 0).send());
        errors.add(admin.client().get("/api/console/companies").query("page", "abc").send());
        errors.add(admin.client().post("/api/console/companies").jsonText("{\"code\": ").send());
        errors.add(admin.client().post("/api/console/companies").jsonText("[1, 2]").send());
        errors.add(admin.client().post("/api/console/companies")
                .json(Map.of("code", taken, "name", "N", "legalName", "L")).send());
        errors.add(admin.client().post("/api/console/invitations")
                .json(Map.of("email", takenEmail, "name", "N", "role", "AGENT")).send());
        errors.add(admin.client().post("/api/console/invitations")
                .json(Map.of("email", "x@example.test", "name", "N", "role", "OWNER")).send());
        errors.add(admin.client().get("/api/console/no-such-endpoint").send());
        errors.add(admin.client().post("/api/console/companies").csrfToken("wrong").json(Map.of()).send());

        assertThat(errors).extracting(ApiResponse::status)
                .containsExactly(401, 403, 404, 400, 400, 400, 400, 409, 409, 400, 404, 403);
        SoftAssertions soft = new SoftAssertions();
        errors.forEach(r -> assertErrorBody(soft, r));
        soft.assertAll();
    }

    @Test
    @DisplayName("S0-AC-85 fields without a value are present as null and empty lists are []")
    void missingValuesAreNullAndEmptyListsAreEmpty() {
        OpsWorld w = OpsWorld.build(fixtures);

        JsonNode issue = w.agentA().client().get(w.issuePath(w.agentA())).expect(200).json();
        assertThat(issue.has("firstResponseAt")).as("firstResponseAt in %s", issue).isTrue();
        assertThat(issue.path("firstResponseAt").isNull()).as("firstResponseAt in %s", issue).isTrue();

        JsonNode attachment = w.customerA().client().get(w.issuePath(w.customerA()) + "/attachments").expect(200)
                .json().path("items").path(0);
        assertThat(attachment.has("deletedAt")).as("deletedAt in %s", attachment).isTrue();
        assertThat(attachment.path("deletedAt").isNull()).as("deletedAt in %s", attachment).isTrue();
        assertThat(attachment.has("commentId")).as("commentId in %s", attachment).isTrue();
        assertThat(attachment.path("commentId").isNull()).as("commentId in %s", attachment).isTrue();

        JsonNode companies = w.agentNowhere().client().get("/api/console/companies").expect(200).json();
        assertThat(companies.path("items").isArray()).as("items of an unassigned agent: %s", companies).isTrue();
        assertThat(companies.path("items")).as("items of an unassigned agent").isEmpty();

        JsonNode inbox = w.agentNowhere().client().get("/api/console/notifications").expect(200).json();
        assertThat(inbox.path("items").isArray()).as("inbox items: %s", inbox).isTrue();
        assertThat(inbox.path("items")).as("inbox of a new agent").isEmpty();

        JsonNode pending = OpsChecks.allItems(w.admin().client(), "/api/console/invitations").stream()
                .filter(n -> n.path("accountId").asLong(-1) == w.invitedAgent().accountId())
                .findFirst()
                .orElseThrow(() -> new AssertionError("No pending invitation " + w.invitedAgent().accountId()));
        assertThat(pending.path("companies").isArray()).as("companies of a staff invitation: %s", pending).isTrue();
        assertThat(pending.path("companies")).as("companies of a staff invitation").isEmpty();

        JsonNode past = w.admin().client().get("/api/console/companies").query("page", 100_000).expect(200).json();
        assertThat(past.path("items").isArray()).as("items past the last page: %s", past).isTrue();
        assertThat(past.path("items")).as("items past the last page").isEmpty();
    }
}
