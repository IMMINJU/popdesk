package io.popdesk.issue;

import static io.popdesk.issue.IssueSteps.fieldCode;
import static io.popdesk.issue.IssueSteps.fieldNames;
import static io.popdesk.issue.IssueSteps.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * {@code PATCH .../issues/{key}}: the reporting customer on the portal and staff on the console
 * change the title, the content, and the priority, with the {@code version} they read.
 */
class IssueEditTest extends BareAppTest {

    /** Who edits: the customer who filed the issue, on the portal, or an assigned agent, on the console. */
    enum Editor {
        REPORTER,
        AGENT;

        Session of(World w) {
            return this == REPORTER ? w.reporter() : w.agent();
        }
    }

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    /** A body with {@code version} and the given fields (name, value, ...); a value may be null. */
    private static Map<String, Object> body(Long version, Object... fields) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < fields.length; i += 2) {
            body.put((String) fields[i], fields[i + 1]);
        }
        if (version != null) {
            body.put("version", version);
        }
        return body;
    }

    private static ApiResponse patch(Session session, String key, Map<String, Object> body) {
        return session.client().patch(session.api("/issues/" + key)).json(body).send();
    }

    private Issue file(World w) {
        return fixtures.fileIssue(w.reporter(), NewIssue.in(w.code()).content("First content.").priority("HIGH"));
    }

    // S3-AC-18

    @ParameterizedTest(name = "{0}")
    @EnumSource(Editor.class)
    @DisplayName("S3-AC-18 an edit stores only the fields sent, raises the version by 1 each time, and a null content clears it")
    void editChangesOnlyTheFieldsSent(Editor editor) {
        World w = flow.world();
        Session who = editor.of(w);
        Issue issue = file(w);
        long version = flow.version(who, issue.key());
        String newTitle = Unique.text("Edited title");
        flow.tick();

        JsonNode titled = patch(who, issue.key(), body(version, "title", "  " + newTitle + "  ")).expectStatus(200)
                .json();

        assertThat(text(titled, "title")).as("title in the response, trimmed").isEqualTo(newTitle);
        assertThat(text(titled, "content")).as("content, not sent").isEqualTo("First content.");
        assertThat(text(titled, "priority")).as("priority, not sent").isEqualTo("HIGH");
        assertThat(titled.path("version").asLong(-1)).as("version after the first edit").isEqualTo(version + 1);
        JsonNode reread = flow.detail(who, issue.key());
        assertThat(text(reread, "title")).isEqualTo(newTitle);
        assertThat(reread.path("version").asLong(-1)).as("version read again").isEqualTo(version + 1);

        JsonNode prioritized = patch(who, issue.key(), body(version + 1, "priority", "LOW")).expectStatus(200).json();

        assertThat(text(prioritized, "priority")).isEqualTo("LOW");
        assertThat(text(prioritized, "title")).as("title, not sent").isEqualTo(newTitle);
        assertThat(text(prioritized, "content")).as("content, not sent").isEqualTo("First content.");
        assertThat(prioritized.path("version").asLong(-1)).as("version after the second edit").isEqualTo(version + 2);

        JsonNode cleared = patch(who, issue.key(), body(version + 2, "content", null)).expectStatus(200).json();

        assertThat(text(cleared, "content")).as("content after content: null").isEmpty();
        assertThat(cleared.path("version").asLong(-1)).as("version after the third edit").isEqualTo(version + 3);
        JsonNode staffView = flow.detail(w.admin(), issue.key());
        assertThat(text(staffView, "title")).isEqualTo(newTitle);
        assertThat(text(staffView, "priority")).isEqualTo("LOW");
        assertThat(text(staffView, "content")).isEmpty();
        assertThat(staffView.path("version").asLong(-1)).isEqualTo(version + 3);
    }

    @ParameterizedTest(name = "{0}: null")
    @ValueSource(strings = {"title", "priority"})
    @DisplayName("S3-AC-18 a null title or priority is 400 with that field, and nothing changes")
    void nullTitleOrPriorityIsRefused(String field) {
        World w = flow.world();
        Issue issue = file(w);
        JsonNode before = flow.detail(w.admin(), issue.key());

        ApiResponse refused = patch(w.reporter(), issue.key(), body(flow.version(w.reporter(), issue.key()), field,
                null));

        assertThat(refused.status()).as(refused.request()).isEqualTo(400);
        assertThat(refused.errorCode()).isEqualTo("INVALID_INPUT");
        assertThat(fieldNames(refused)).contains(field);
        assertThat(flow.detail(w.admin(), issue.key())).as("the issue after the refused edit").isEqualTo(before);
    }

    static Stream<Arguments> wrongValues() {
        return Stream.of(
                Arguments.of("blank title", "title", "    ", "BLANK"),
                Arguments.of("title of 201", "title", "a".repeat(201), "TOO_LONG"),
                Arguments.of("content of 10,001", "content", "a".repeat(10_001), "TOO_LONG"),
                Arguments.of("unknown priority", "priority", "CRITICAL", "UNKNOWN_VALUE"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wrongValues")
    @DisplayName("S3-AC-18 an edit follows the input rules of filing: a wrong value is 400 with its field and code, and nothing changes")
    void editKeepsTheRulesOfFiling(String what, String field, String value, String code) {
        World w = flow.world();
        Issue issue = file(w);
        JsonNode before = flow.detail(w.admin(), issue.key());

        ApiResponse refused = patch(w.agent(), issue.key(), body(flow.version(w.agent(), issue.key()), field, value));

        assertThat(refused.status()).as(refused.request()).isEqualTo(400);
        assertThat(refused.errorCode()).isEqualTo("INVALID_INPUT");
        assertThat(fieldCode(refused, field)).as("code of field %s", field).isEqualTo(code);
        assertThat(flow.detail(w.admin(), issue.key())).as("the issue after the refused edit").isEqualTo(before);
    }

    // S3-AC-19

    @ParameterizedTest(name = "{0}")
    @EnumSource(Editor.class)
    @DisplayName("S3-AC-19 an edit with an old version is 409 VERSION_CONFLICT and changes nothing")
    void oldVersionIsAConflict(Editor editor) {
        World w = flow.world();
        Session who = editor.of(w);
        Issue issue = file(w);
        long read = flow.version(who, issue.key());
        flow.edit(w.admin(), issue.key(), Map.of("priority", "URGENT"));
        JsonNode before = flow.detail(w.admin(), issue.key());

        ApiResponse conflict = patch(who, issue.key(), body(read, "title", Unique.text("Too late")));

        assertThat(conflict.status()).as(conflict.request()).isEqualTo(409);
        assertThat(conflict.errorCode()).isEqualTo("VERSION_CONFLICT");
        assertThat(flow.detail(w.admin(), issue.key())).as("the issue after the conflict").isEqualTo(before);
    }

    @Test
    @DisplayName("S3-AC-19 an edit without version is 400 with field version, and nothing changes")
    void missingVersionIsInvalid() {
        World w = flow.world();
        Issue issue = file(w);
        JsonNode before = flow.detail(w.admin(), issue.key());

        ApiResponse refused = patch(w.reporter(), issue.key(), body(null, "title", Unique.text("No version")));

        assertThat(refused.status()).as(refused.request()).isEqualTo(400);
        assertThat(refused.errorCode()).isEqualTo("INVALID_INPUT");
        assertThat(fieldNames(refused)).contains("version");
        assertThat(flow.detail(w.admin(), issue.key())).as("the issue after the refused edit").isEqualTo(before);
    }

    @Test
    @DisplayName("S3-AC-19 a status change by staff makes the version the customer read old: 409, then 200 with the new version")
    void statusChangeMakesTheReadVersionOld() {
        World w = flow.world();
        Issue issue = file(w);
        long read = flow.version(w.reporter(), issue.key());
        flow.changeStatus(w.agent(), issue.key(), "CONFIRMED");
        JsonNode before = flow.detail(w.admin(), issue.key());
        String newTitle = Unique.text("After the status change");

        ApiResponse conflict = patch(w.reporter(), issue.key(), body(read, "title", newTitle));

        assertThat(conflict.status()).as(conflict.request()).isEqualTo(409);
        assertThat(conflict.errorCode()).isEqualTo("VERSION_CONFLICT");
        assertThat(flow.detail(w.admin(), issue.key())).as("the issue after the conflict").isEqualTo(before);
        JsonNode edited = patch(w.reporter(), issue.key(), body(before.path("version").asLong(), "title", newTitle))
                .expectStatus(200).json();
        assertThat(text(edited, "title")).isEqualTo(newTitle);
        assertThat(text(edited, "status")).isEqualTo("CONFIRMED");
    }

    // S3-AC-20

    @Test
    @DisplayName("S3-AC-20 a colleague of the same company who did not file the issue gets 403, and nothing changes")
    void colleagueCannotEdit() {
        World w = flow.world();
        Issue issue = file(w);
        JsonNode before = flow.detail(w.admin(), issue.key());

        ApiResponse refused = patch(w.colleague(), issue.key(), body(flow.version(w.colleague(), issue.key()), "title",
                Unique.text("By a colleague"), "content", "Not mine.", "priority", "LOW"));

        assertThat(refused.status()).as(refused.request()).isEqualTo(403);
        assertThat(refused.errorCode()).isEqualTo("FORBIDDEN");
        assertThat(flow.detail(w.admin(), issue.key())).as("the issue after the colleague's edit").isEqualTo(before);
    }

    @Test
    @DisplayName("S3-AC-20 the reporter taken out of the company gets 403 in the same session, and nothing changes")
    void reporterTakenOutOfTheCompanyCannotEdit() {
        World w = flow.world();
        Issue issue = file(w);
        long version = flow.version(w.reporter(), issue.key());
        flow.removeCustomer(w.admin(), w.code(), w.reporter().accountId());
        JsonNode before = flow.detail(w.admin(), issue.key());

        ApiResponse refused = patch(w.reporter(), issue.key(), body(version, "title", Unique.text("After leaving")));

        assertThat(refused.status()).as(refused.request()).isEqualTo(403);
        assertThat(refused.errorCode()).isEqualTo("FORBIDDEN");
        assertThat(flow.detail(w.admin(), issue.key())).as("the issue after the refused edit").isEqualTo(before);
    }

    // S3-AC-21

    @ParameterizedTest(name = "{0}")
    @EnumSource(Editor.class)
    @DisplayName("S3-AC-21 an edit of a CLOSED issue is 400 ISSUE_CLOSED, and nothing changes")
    void closedIssueCannotBeEdited(Editor editor) {
        World w = flow.world();
        Session who = editor.of(w);
        Issue issue = file(w);
        flow.close(w.reporter(), issue.key());
        JsonNode before = flow.detail(w.admin(), issue.key());

        ApiResponse refused = patch(who, issue.key(), body(before.path("version").asLong(), "title",
                Unique.text("After closing")));

        assertThat(refused.status()).as(refused.request()).isEqualTo(400);
        assertThat(refused.errorCode()).isEqualTo("ISSUE_CLOSED");
        assertThat(flow.detail(w.admin(), issue.key())).as("the closed issue after the refused edit").isEqualTo(before);
    }

    // S3-AC-22

    @ParameterizedTest(name = "{0}")
    @EnumSource(Editor.class)
    @DisplayName("S3-AC-22 an edit that sends the values the issue already has is 200 and keeps version and updatedAt")
    void sameValuesChangeNothing(Editor editor) {
        World w = flow.world();
        Session who = editor.of(w);
        Issue issue = file(w);
        JsonNode before = flow.detail(who, issue.key());
        flow.tick();

        JsonNode same = patch(who, issue.key(), body(before.path("version").asLong(), "title", text(before, "title"),
                "content", "First content.", "priority", "HIGH")).expectStatus(200).json();

        assertThat(same.path("version").asLong(-1)).as("version in the response")
                .isEqualTo(before.path("version").asLong());
        assertThat(text(same, "updatedAt")).as("updatedAt in the response").isEqualTo(text(before, "updatedAt"));
        assertThat(flow.detail(who, issue.key())).as("the issue read again").isEqualTo(before);
    }

    // S3-AC-23

    @Test
    @DisplayName("S3-AC-23 a customer's edit that also sends private, companyCode, status, assignee, and reporterId changes none of them")
    void portalEditTakesNoOtherField() {
        World w = flow.world();
        Company other = fixtures.createCompany(w.admin());
        Issue issue = file(w);
        String newTitle = Unique.text("Only the title");

        JsonNode edited = patch(w.reporter(), issue.key(), body(flow.version(w.reporter(), issue.key()),
                "title", newTitle, "private", true, "companyCode", other.code(), "status", "CLOSED",
                "assignee", w.agent().accountId(), "reporterId", w.colleague().accountId())).expectStatus(200).json();

        assertThat(text(edited, "title")).isEqualTo(newTitle);
        JsonNode staffView = flow.detail(w.admin(), issue.key());
        assertThat(text(staffView, "title")).isEqualTo(newTitle);
        assertThat(staffView.path("private").asBoolean(true)).as("private").isFalse();
        assertThat(text(staffView, "companyCode")).isEqualTo(w.code());
        assertThat(text(staffView, "status")).isEqualTo("RECEIVED");
        assertThat(staffView.path("assignee").isNull()).as("assignee is null in %s", staffView).isTrue();
        assertThat(staffView.path("reporter").path("accountId").asLong(-1)).as("reporter")
                .isEqualTo(w.reporter().accountId());
        w.colleague().client().get("/api/portal/issues/" + issue.key()).expect(200);
    }

    @Test
    @DisplayName("S3-AC-23 staff's edit that also sends private and companyCode changes neither")
    void consoleEditCannotChangePrivateOrCompany() {
        World w = flow.world();
        Company other = fixtures.createCompany(w.admin());
        Issue issue = file(w);
        String newTitle = Unique.text("Only the title");

        JsonNode edited = patch(w.admin(), issue.key(), body(flow.version(w.admin(), issue.key()),
                "title", newTitle, "private", true, "companyCode", other.code())).expectStatus(200).json();

        assertThat(text(edited, "title")).isEqualTo(newTitle);
        JsonNode staffView = flow.detail(w.admin(), issue.key());
        assertThat(text(staffView, "key")).isEqualTo(issue.key());
        assertThat(staffView.path("private").asBoolean(true)).as("private").isFalse();
        assertThat(text(staffView, "companyCode")).isEqualTo(w.code());
        w.colleague().client().get("/api/portal/issues/" + issue.key()).expect(200);
    }
}
