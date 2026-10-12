package io.popdesk.issue;

import static io.popdesk.issue.IssueFlow.NO_KEY;
import static io.popdesk.issue.IssueFlow.editRequest;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
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

        JsonNode titled = editRequest(who, issue.key(), body(version, "title", "  " + newTitle + "  ")).expect(200)
                .json();

        assertThat(text(titled, "title")).as("title in the response, trimmed").isEqualTo(newTitle);
        assertThat(text(titled, "content")).as("content, not sent").isEqualTo("First content.");
        assertThat(text(titled, "priority")).as("priority, not sent").isEqualTo("HIGH");
        assertThat(titled.path("version").asLong(-1)).as("version after the first edit").isEqualTo(version + 1);
        JsonNode reread = flow.detail(who, issue.key());
        assertThat(text(reread, "title")).isEqualTo(newTitle);
        assertThat(reread.path("version").asLong(-1)).as("version read again").isEqualTo(version + 1);

        JsonNode prioritized = editRequest(who, issue.key(), body(version + 1, "priority", "LOW")).expect(200).json();

        assertThat(text(prioritized, "priority")).isEqualTo("LOW");
        assertThat(text(prioritized, "title")).as("title, not sent").isEqualTo(newTitle);
        assertThat(text(prioritized, "content")).as("content, not sent").isEqualTo("First content.");
        assertThat(prioritized.path("version").asLong(-1)).as("version after the second edit").isEqualTo(version + 2);

        JsonNode cleared = editRequest(who, issue.key(), body(version + 2, "content", null)).expect(200).json();

        assertThat(text(cleared, "content")).as("content after content: null").isEmpty();
        assertThat(cleared.path("version").asLong(-1)).as("version after the third edit").isEqualTo(version + 3);
        JsonNode staffView = flow.detail(w.admin(), issue.key());
        assertThat(text(staffView, "title")).isEqualTo(newTitle);
        assertThat(text(staffView, "priority")).isEqualTo("LOW");
        assertThat(text(staffView, "content")).isEmpty();
        assertThat(staffView.path("version").asLong(-1)).isEqualTo(version + 3);
    }

    static Stream<Arguments> wrongValues() {
        return Stream.of(
                Arguments.of("null title", Editor.REPORTER, "title", null, null),
                Arguments.of("null priority", Editor.REPORTER, "priority", null, null),
                Arguments.of("blank title", Editor.AGENT, "title", "    ", "BLANK"),
                Arguments.of("title of 201", Editor.AGENT, "title", "a".repeat(201), "TOO_LONG"),
                Arguments.of("content of 10,001", Editor.AGENT, "content", "a".repeat(10_001), "TOO_LONG"),
                Arguments.of("unknown priority", Editor.AGENT, "priority", "CRITICAL", "UNKNOWN_VALUE"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wrongValues")
    @DisplayName("S3-AC-18 a null title or priority, and a value against the input rules of filing, is 400 with its field (and the code of the rule), and nothing changes")
    void wrongValueIsRefused(String what, Editor editor, String field, String value, String code) {
        World w = flow.world();
        Session who = editor.of(w);
        Issue issue = file(w);
        JsonNode before = flow.detail(w.admin(), issue.key());

        ApiResponse refused = editRequest(who, issue.key(), body(flow.version(who, issue.key()), field, value)).send();

        assertThat(refused.status()).as(refused.request()).isEqualTo(400);
        assertThat(refused.errorCode()).isEqualTo("INVALID_INPUT");
        assertThat(fieldNames(refused)).as("fields of %s", refused.snippet()).contains(field);
        if (code != null) {
            assertThat(fieldCode(refused, field)).as("code of field %s", field).isEqualTo(code);
        }
        assertThat(flow.detail(w.admin(), issue.key())).as("the issue after the refused edit").isEqualTo(before);
    }

    // S3-AC-19

    @ParameterizedTest(name = "{0}, after {1}")
    @CsvSource({"REPORTER, an edit by staff", "AGENT, an edit by staff", "REPORTER, a status change by staff"})
    @DisplayName("S3-AC-19 an edit with the version read before staff edited the issue or changed its status is 409 VERSION_CONFLICT and changes nothing; with the new version it is 200")
    void oldVersionIsAConflict(Editor editor, String change) {
        World w = flow.world();
        Session who = editor.of(w);
        Issue issue = file(w);
        long read = flow.version(who, issue.key());
        if (change.startsWith("a status change")) {
            flow.changeStatus(w.agent(), issue.key(), "CONFIRMED");
        } else {
            flow.edit(w.admin(), issue.key(), Map.of("priority", "URGENT"));
        }
        JsonNode before = flow.detail(w.admin(), issue.key());
        String newTitle = Unique.text("Too late");

        ApiResponse conflict = editRequest(who, issue.key(), body(read, "title", newTitle)).send();

        assertThat(conflict.status()).as(conflict.request()).isEqualTo(409);
        assertThat(conflict.errorCode()).isEqualTo("VERSION_CONFLICT");
        assertThat(flow.detail(w.admin(), issue.key())).as("the issue after the conflict").isEqualTo(before);
        JsonNode edited = editRequest(who, issue.key(), body(before.path("version").asLong(), "title", newTitle))
                .expect(200).json();
        assertThat(text(edited, "title")).isEqualTo(newTitle);
        assertThat(text(edited, "status")).as("status, as staff left it").isEqualTo(text(before, "status"));
    }

    @Test
    @DisplayName("S3-AC-19 an edit without version is 400 with field version, and nothing changes")
    void missingVersionIsInvalid() {
        World w = flow.world();
        Issue issue = file(w);
        JsonNode before = flow.detail(w.admin(), issue.key());

        ApiResponse refused = editRequest(w.reporter(), issue.key(), body(null, "title", Unique.text("No version")))
                .send();

        assertThat(refused.status()).as(refused.request()).isEqualTo(400);
        assertThat(refused.errorCode()).isEqualTo("INVALID_INPUT");
        assertThat(fieldNames(refused)).contains("version");
        assertThat(flow.detail(w.admin(), issue.key())).as("the issue after the refused edit").isEqualTo(before);
    }

    // S3-AC-20

    @Test
    @DisplayName("S3-AC-20 a colleague of the same company who did not file the issue gets 403, and for a private issue the 404 of a key nobody has; nothing changes")
    void colleagueCannotEdit() {
        World w = flow.world();
        Issue issue = file(w);
        Issue hidden = fixtures.fileIssue(w.reporter(), NewIssue.in(w.code()).privateIssue());
        JsonNode before = flow.detail(w.admin(), issue.key());
        JsonNode hiddenBefore = flow.detail(w.admin(), hidden.key());
        Map<String, Object> ofHidden = body(hiddenBefore.path("version").asLong(), "title", Unique.text("Not seen"));

        ApiResponse refused = editRequest(w.colleague(), issue.key(), body(flow.version(w.colleague(), issue.key()), "title",
                Unique.text("By a colleague"), "content", "Not mine.", "priority", "LOW")).send();

        assertThat(refused.status()).as(refused.request()).isEqualTo(403);
        assertThat(refused.errorCode()).isEqualTo("FORBIDDEN");
        assertThat(flow.detail(w.admin(), issue.key())).as("the issue after the colleague's edit").isEqualTo(before);

        ApiResponse notFound = editRequest(w.colleague(), hidden.key(), ofHidden).send();
        ApiResponse missing = editRequest(w.colleague(), NO_KEY, ofHidden).send();

        assertThat(missing.status()).as(missing.request()).isEqualTo(404);
        assertThat(notFound.status()).as(notFound.request() + " -> " + notFound.snippet()).isEqualTo(404);
        assertThat(notFound.errorCode()).isEqualTo("NOT_FOUND");
        assertThat(notFound.text()).as("body, like an issue key nobody has").isEqualTo(missing.text());
        assertThat(flow.detail(w.admin(), hidden.key())).as("the private issue after the colleague's edit")
                .isEqualTo(hiddenBefore);
    }

    @Test
    @DisplayName("S3-AC-20 the reporter taken out of the company gets 403 in the same session, and nothing changes")
    void reporterTakenOutOfTheCompanyCannotEdit() {
        World w = flow.world();
        Issue issue = file(w);
        long version = flow.version(w.reporter(), issue.key());
        flow.removeCustomer(w.admin(), w.code(), w.reporter().accountId());
        JsonNode before = flow.detail(w.admin(), issue.key());

        ApiResponse refused = editRequest(w.reporter(), issue.key(), body(version, "title", Unique.text("After leaving")))
                .send();

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

        ApiResponse refused = editRequest(who, issue.key(), body(before.path("version").asLong(), "title",
                Unique.text("After closing"))).send();

        assertThat(refused.status()).as(refused.request()).isEqualTo(400);
        assertThat(refused.errorCode()).isEqualTo("ISSUE_CLOSED");
        assertThat(flow.detail(w.admin(), issue.key())).as("the closed issue after the refused edit").isEqualTo(before);
    }

    @Test
    @DisplayName("S3-AC-21 an edit of a CLOSED issue with the version read before it was closed is 400 ISSUE_CLOSED, not a conflict, and nothing changes")
    void closedComesBeforeAnOldVersion() {
        World w = flow.world();
        Issue issue = file(w);
        long read = flow.version(w.reporter(), issue.key());
        flow.close(w.reporter(), issue.key());
        JsonNode before = flow.detail(w.admin(), issue.key());
        assertThat(before.path("version").asLong(-1)).as("version after closing").isGreaterThan(read);

        for (Session who : List.of(w.reporter(), w.agent())) {
            ApiResponse refused = editRequest(who, issue.key(), body(read, "title", Unique.text("Closed meanwhile")))
                    .send();

            assertThat(refused.status()).as(refused.request() + " -> " + refused.snippet()).isEqualTo(400);
            assertThat(refused.errorCode()).as("code for %s", who.role()).isEqualTo("ISSUE_CLOSED");
        }
        assertThat(flow.detail(w.admin(), issue.key())).as("the closed issue after the refused edits").isEqualTo(before);
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

        JsonNode same = editRequest(who, issue.key(), body(before.path("version").asLong(), "title", text(before, "title"),
                "content", "First content.", "priority", "HIGH")).expect(200).json();

        assertThat(same.path("version").asLong(-1)).as("version in the response")
                .isEqualTo(before.path("version").asLong());
        assertThat(text(same, "updatedAt")).as("updatedAt in the response").isEqualTo(text(before, "updatedAt"));
        assertThat(flow.detail(who, issue.key())).as("the issue read again").isEqualTo(before);
    }

    // S3-AC-23

    @Test
    @DisplayName("S3-AC-23 a customer's edit that also sends private, companyCode, status, assignee, reporterId, createdBy, and channel is taken and changes none of them")
    void portalEditTakesNoOtherField() {
        World w = flow.world();
        Company other = fixtures.createCompany(w.admin());
        Issue issue = file(w);
        String newTitle = Unique.text("Only the title");

        JsonNode edited = editRequest(w.reporter(), issue.key(), body(flow.version(w.reporter(), issue.key()),
                "title", newTitle, "private", true, "companyCode", other.code(), "status", "CLOSED",
                "assignee", w.agent().accountId(), "reporterId", w.colleague().accountId(),
                "createdBy", w.agent().accountId(), "channel", "PHONE")).expect(200).json();

        assertThat(text(edited, "title")).isEqualTo(newTitle);
        JsonNode staffView = flow.detail(w.admin(), issue.key());
        assertThat(text(staffView, "title")).isEqualTo(newTitle);
        assertThat(staffView.path("private").asBoolean(true)).as("private").isFalse();
        assertThat(text(staffView, "companyCode")).isEqualTo(w.code());
        assertThat(text(staffView, "status")).isEqualTo("RECEIVED");
        assertThat(staffView.path("assignee").isNull()).as("assignee is null in %s", staffView).isTrue();
        assertThat(staffView.path("reporter").path("accountId").asLong(-1)).as("reporter")
                .isEqualTo(w.reporter().accountId());
        assertThat(staffView.path("createdBy").isNull()).as("createdBy is null in %s", staffView).isTrue();
        assertThat(text(staffView, "channel")).as("channel").isEqualTo("PORTAL");
        w.colleague().client().get("/api/portal/issues/" + issue.key()).expect(200);
    }

    @Test
    @DisplayName("S3-AC-23 staff's edit that also sends private and companyCode changes neither")
    void consoleEditCannotChangePrivateOrCompany() {
        World w = flow.world();
        Company other = fixtures.createCompany(w.admin());
        Issue issue = file(w);
        String newTitle = Unique.text("Only the title");

        JsonNode edited = editRequest(w.admin(), issue.key(), body(flow.version(w.admin(), issue.key()),
                "title", newTitle, "private", true, "companyCode", other.code())).expect(200).json();

        assertThat(text(edited, "title")).isEqualTo(newTitle);
        JsonNode staffView = flow.detail(w.admin(), issue.key());
        assertThat(text(staffView, "key")).isEqualTo(issue.key());
        assertThat(staffView.path("private").asBoolean(true)).as("private").isFalse();
        assertThat(text(staffView, "companyCode")).isEqualTo(w.code());
        w.colleague().client().get("/api/portal/issues/" + issue.key()).expect(200);
    }
}
