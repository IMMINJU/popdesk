package io.popdesk.privacy;

import static io.popdesk.issue.TalkFlow.assertRefused;
import static io.popdesk.issue.TalkFlow.isNull;
import static io.popdesk.privacy.CompanyExportSteps.EXPORT_FIELDS;
import static io.popdesk.privacy.CompanyExportSteps.awaitEnded;
import static io.popdesk.privacy.CompanyExportSteps.awaitStoredExpired;
import static io.popdesk.privacy.CompanyExportSteps.createRequest;
import static io.popdesk.privacy.CompanyExportSteps.done;
import static io.popdesk.privacy.CompanyExportSteps.exportFiles;
import static io.popdesk.privacy.CompanyExportSteps.exportsPath;
import static io.popdesk.privacy.CompanyExportSteps.fieldNames;
import static io.popdesk.privacy.CompanyExportSteps.holdUpdatesOf;
import static io.popdesk.privacy.CompanyExportSteps.id;
import static io.popdesk.privacy.CompanyExportSteps.list;
import static io.popdesk.privacy.CompanyExportSteps.listed;
import static io.popdesk.privacy.CompanyExportSteps.start;
import static io.popdesk.privacy.CompanyExportSteps.state;
import static io.popdesk.privacy.CompanyExportSteps.storedState;
import static io.popdesk.privacy.ExportSteps.time;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.HeldWrite;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import io.popdesk.task.TaskSteps;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * Asking for a company export, one at a time, the list, and the end of a ZIP's 24 hours (S10B-AC-01, S10B-AC-02,
 * S10B-AC-03, S10B-AC-08).
 */
class CompanyExportJobTest extends CompanyExportAppTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ACTIVE", "ARCHIVED", "SUSPENDED"})
    @DisplayName("S10B-AC-01 an admin's export of an ACTIVE, ARCHIVED, or SUSPENDED company answers 202 with the "
            + "export as RUNNING (ID, who asked, when, the two choices, no failure, end, or size) and then ends DONE")
    void askingAnswers202WithARunningExport(String status) {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        if (!status.equals("ACTIVE")) {
            flow.companyStatus(admin, company.code(), status);
        }
        Instant asked = clock.instant();

        JsonNode answer = start(admin, company.code(), true, false);

        assertThat(fieldNames(answer)).as("fields of the answer").containsExactlyInAnyOrderElementsOf(EXPORT_FIELDS);
        assertThat(state(answer)).as("state in %s", answer).isEqualTo("RUNNING");
        assertThat(id(answer)).as("id").matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(answer.path("requestedBy").path("accountId").asLong(-1)).as("requestedBy.accountId")
                .isEqualTo(admin.accountId());
        assertThat(answer.path("requestedBy").path("name").asString(null)).as("requestedBy.name")
                .isEqualTo(admin.name());
        assertThat(time(answer, "requestedAt")).as("requestedAt").isEqualTo(asked);
        assertThat(answer.path("includeInternal").asBoolean(false)).as("includeInternal").isTrue();
        assertThat(answer.path("includePrivate").asBoolean(true)).as("includePrivate").isFalse();
        assertThat(isNull(answer, "failure") && isNull(answer, "finishedAt") && isNull(answer, "size"))
                .as("failure, finishedAt, and size are null in %s", answer).isTrue();
        assertThat(state(awaitEnded(admin, company.code(), id(answer)))).as("state at the end").isEqualTo("DONE");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "{\"includePrivate\": false}|includeInternal",
        "{\"includeInternal\": null, \"includePrivate\": false}|includeInternal",
        "{\"includeInternal\": false}|includePrivate",
        "{\"includeInternal\": false, \"includePrivate\": null}|includePrivate",
        "{}|includeInternal"})
    @DisplayName("S10B-AC-01 a body without includeInternal or includePrivate, or with null, is 400 INVALID_INPUT "
            + "with REQUIRED for that field, and no export is made")
    void bothChoicesAreRequired(String body, String field) {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);

        ApiResponse answer = admin.client().post(exportsPath(company.code())).jsonText(body).send();

        TaskSteps.assertInvalid(answer, field, "REQUIRED");
        assertThat(list(admin, company.code())).as("exports of %s", company.code()).isEmpty();
    }

    @Test
    @DisplayName("S10B-AC-01 the checks come in the order role (403), company (404), body (400), another export "
            + "running (409)")
    void checksComeInOrder() {
        Session admin = fixtures.signInAdmin();
        Company running = fixtures.createCompany(admin);
        Company other = fixtures.createCompany(admin);
        Session agent = fixtures.agent(admin, other.code());
        String unknown = Unique.companyCode();

        try (HeldWrite end = holdEnd()) {
            String id = id(start(admin, running.code(), false, false));
            try {
                ApiResponse byAgent = agent.client().post(exportsPath(unknown)).jsonText("{}").send();
                ApiResponse unknownCompany = admin.client().post(exportsPath(unknown)).jsonText("{}").send();
                ApiResponse badBody = admin.client().post(exportsPath(other.code())).jsonText("{}").send();
                ApiResponse whileRunning = createRequest(admin, other.code(), false, false).send();

                assertRefused(byAgent, 403, "FORBIDDEN");
                assertRefused(unknownCompany, 404, "NOT_FOUND");
                assertRefused(badBody, 400, "INVALID_INPUT");
                assertRefused(whileRunning, 409, "EXPORT_RUNNING");
                assertThat(state(listed(admin, running.code(), id))).as("the held export").isEqualTo("RUNNING");
            } finally {
                end.release();
            }
            awaitEnded(admin, running.code(), id);
        }
    }

    @Test
    @DisplayName("S10B-AC-02 while an export of one company is RUNNING, asking for one of another company or of the "
            + "same is 409 EXPORT_RUNNING and makes no export; once it ended, the other company's export starts")
    void oneExportRunsAtATime() {
        Session admin = fixtures.signInAdmin();
        Session otherAdmin = fixtures.admin(admin);
        Company a = fixtures.createCompany(admin);
        Company b = fixtures.createCompany(admin);
        String id;

        try (HeldWrite end = holdEnd()) {
            id = id(start(admin, a.code(), false, false));
            try {
                ApiResponse ofB = createRequest(otherAdmin, b.code(), true, true).send();
                ApiResponse ofA = createRequest(admin, a.code(), false, false).send();

                assertRefused(ofB, 409, "EXPORT_RUNNING");
                assertRefused(ofA, 409, "EXPORT_RUNNING");
                assertThat(list(admin, b.code())).as("exports of %s", b.code()).isEmpty();
                assertThat(list(admin, a.code())).as("exports of %s", a.code()).extracting(CompanyExportSteps::id)
                        .containsExactly(id);
            } finally {
                end.release();
            }
        }

        assertThat(state(awaitEnded(admin, a.code(), id))).as("the first export").isEqualTo("DONE");
        JsonNode ofB = start(otherAdmin, b.code(), true, true);
        assertThat(state(ofB)).as("the export of %s afterwards", b.code()).isEqualTo("RUNNING");
        awaitEnded(otherAdmin, b.code(), id(ofB));
    }

    @Test
    @DisplayName("S10B-AC-03 the list holds the company's exports, the latest asked for first, with state, times, "
            + "size, and failure, and none of another company")
    void listIsLatestFirst() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Company other = fixtures.createCompany(admin);
        fixtures.fileIssue(fixtures.customer(admin, company.code()), company.code());
        Map<String, Instant> askedAt = new HashMap<>();

        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofMinutes(1));
            askedAt.put(id(done(admin, company.code(), i == 1, i == 2)), clock.instant());
        }
        String ofOther = id(done(admin, other.code(), false, false));
        List<JsonNode> listed = list(admin, company.code());

        assertThat(listed).extracting(CompanyExportSteps::id).as("IDs, latest first")
                .containsExactlyElementsOf(askedAt.entrySet().stream()
                        .sorted(Map.Entry.<String, Instant>comparingByValue().reversed()).map(Map.Entry::getKey)
                        .toList())
                .doesNotContain(ofOther);
        for (JsonNode export : listed) {
            assertThat(fieldNames(export)).as("fields of %s", export).containsExactlyInAnyOrderElementsOf(EXPORT_FIELDS);
            assertThat(state(export)).as("state of %s", export).isEqualTo("DONE");
            assertThat(time(export, "requestedAt")).as("requestedAt of %s", export).isEqualTo(askedAt.get(id(export)));
            assertThat(time(export, "finishedAt")).as("finishedAt of %s", export).isEqualTo(askedAt.get(id(export)));
            assertThat(export.path("size").asLong(-1)).as("size of %s", export).isPositive();
            assertThat(isNull(export, "failure")).as("failure of %s is null", export).isTrue();
        }
    }

    @Test
    @DisplayName("S10B-AC-03 a DONE export whose ZIP is over 24 hours old is listed as EXPIRED and keeps its end; one "
            + "done less than 24 hours ago stays DONE")
    void overADayOldIsListedExpired() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        String old = id(done(admin, company.code(), false, false));
        Instant oldEnd = clock.instant();
        clock.advance(Duration.ofHours(2));
        String young = id(done(admin, company.code(), false, false));

        Session again;
        JsonNode oldListed;
        // The cleanup job's UPDATE of the old export waits, so the list is read before it stored EXPIRED.
        try (HeldWrite expiry = holdUpdatesOf(db, postgres, old)) {
            clock.advance(Duration.ofHours(22).plusSeconds(1));
            again = fixtures.signInAdmin();
            oldListed = listed(again, company.code(), old);
            assertThat(storedState(db, old)).as("stored state of %s while the cleanup job is held", old)
                    .isEqualTo("DONE");
            expiry.release();
        }

        assertThat(state(oldListed)).as("state of the export done 24 hours and a second ago").isEqualTo("EXPIRED");
        assertThat(time(oldListed, "finishedAt")).as("finishedAt of the expired export").isEqualTo(oldEnd);
        assertThat(state(listed(again, company.code(), young))).as("state of the export done 22 hours ago")
                .isEqualTo("DONE");
        awaitStoredExpired(db, old);
        JsonNode stored = listed(again, company.code(), old);
        assertThat(state(stored)).as("state once stored as EXPIRED").isEqualTo("EXPIRED");
        assertThat(time(stored, "finishedAt")).as("finishedAt once stored as EXPIRED").isEqualTo(oldEnd);
    }

    @Test
    @DisplayName("S10B-AC-08 24 hours after an export became DONE the cleanup job deletes its ZIP and stores it as "
            + "EXPIRED; the export stays in the list")
    void expiryJobDeletesTheZipAndKeepsTheExport() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        String id = id(done(admin, company.code(), false, false));
        assertThat(exportFiles()).as("files in the export folder once %s is DONE", id)
                .anyMatch(name -> name.contains(id));

        clock.advance(Duration.ofHours(24).plusSeconds(1));

        Wait.untilAsserted("the cleanup job stored " + id + " as EXPIRED and deleted its file", () -> {
            assertThat(db.row("select state from " + CompanyExportSteps.TABLE + " where id::text = ?", id)
                    .get("state")).as("stored state of %s", id).isEqualTo("EXPIRED");
            assertThat(exportFiles()).as("files in the export folder").noneMatch(name -> name.contains(id));
        });
        assertThat(state(listed(fixtures.signInAdmin(), company.code(), id))).as("state in the list")
                .isEqualTo("EXPIRED");
    }
}
