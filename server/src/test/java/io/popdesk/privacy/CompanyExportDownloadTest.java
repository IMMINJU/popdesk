package io.popdesk.privacy;

import static io.popdesk.privacy.CompanyExportSteps.awaitEnded;
import static io.popdesk.privacy.CompanyExportSteps.done;
import static io.popdesk.privacy.CompanyExportSteps.filePath;
import static io.popdesk.privacy.CompanyExportSteps.id;
import static io.popdesk.privacy.CompanyExportSteps.start;
import static io.popdesk.privacy.CompanyExportSteps.state;
import static io.popdesk.privacy.CompanyExportSteps.withExportDirAFile;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.TalkFlow;
import io.popdesk.privacy.CompanyExportSteps.Zip;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.HeldWrite;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * Downloading an export's ZIP and what the audit log keeps of it (S10B-AC-07, S10B-AC-09).
 */
class CompanyExportDownloadTest extends CompanyExportAppTest {

    /** Why an export has no file to give. */
    enum NoFile {
        RUNNING, FAILED, OVER_A_DAY
    }

    private static List<JsonNode> entries(Session admin, String action, String code) {
        List<JsonNode> out = new ArrayList<>();
        admin.client().get(PrivacySteps.AUDIT_LOG).query("action", action).query("targetType", "COMPANY")
                .query("targetCode", code).query("size", 100).expect(200).json().path("items").forEach(out::add);
        return out.stream().filter(e -> code.equals(e.path("target").path("code").asString(null))).toList();
    }

    private static void assertHeaders(ApiResponse answer, String code, Instant askedAt) {
        String date = askedAt.atZone(ZoneOffset.UTC).toLocalDate().toString();
        assertThat(answer.status()).as("%s: %s", answer.request(), answer.snippet()).isEqualTo(200);
        assertThat(answer.header("Content-Type")).as("Content-Type of %s", answer.request())
                .startsWith("application/zip");
        assertThat(answer.header("Content-Disposition")).as("Content-Disposition of %s", answer.request())
                .isEqualTo("attachment; filename=\"popdesk-" + code + "-" + date + ".zip\"");
        assertThat(answer.headers("Cache-Control")).as("Cache-Control of %s", answer.request())
                .anySatisfy(v -> assertThat(v.toLowerCase(Locale.ROOT)).contains("no-store"));
    }

    @Test
    @DisplayName("S10B-AC-07 an admin who did not ask downloads the DONE export with GET and HEAD: application/zip, "
            + "attachment popdesk-{code}-{UTC date}.zip, no-store; GET gives the ZIP, HEAD no body")
    void anyAdminDownloads() {
        Session first = fixtures.signInAdmin();
        Session asker = fixtures.admin(first);
        Session other = fixtures.admin(first);
        Company company = fixtures.createCompany(first);
        fixtures.fileIssue(fixtures.customer(first, company.code()), company.code());
        Instant askedAt = clock.instant();
        String id = id(done(asker, company.code(), false, false));

        ApiResponse head = other.client().head(filePath(id)).send();
        ApiResponse get = other.client().get(filePath(id)).send();

        assertHeaders(head, company.code(), askedAt);
        assertHeaders(get, company.code(), askedAt);
        assertThat(head.body()).as("body of HEAD").isEmpty();
        assertThat(Zip.of(get.body()).names()).as("entries of the downloaded ZIP").contains("manifest.json");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(NoFile.class)
    @DisplayName("S10B-AC-07 an export that is RUNNING, FAILED, or DONE over 24 hours ago (also before the cleanup "
            + "job ran, and once it is EXPIRED) is 404 NOT_FOUND for GET and HEAD and records no download")
    void noFileIsNotFound(NoFile why) {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        List<ApiResponse> answers = new ArrayList<>();
        Session reader = admin;
        String id;

        switch (why) {
            case RUNNING -> {
                try (HeldWrite end = holdEnd()) {
                    id = id(start(admin, company.code(), false, false));
                    try {
                        answers.add(admin.client().get(filePath(id)).send());
                        answers.add(admin.client().head(filePath(id)).send());
                        assertThat(state(CompanyExportSteps.listed(admin, company.code(), id)))
                                .as("the held export").isEqualTo("RUNNING");
                    } finally {
                        end.release();
                    }
                }
                awaitEnded(admin, company.code(), id);
            }
            case FAILED -> {
                List<String> ids = new ArrayList<>();
                withExportDirAFile(() -> ids.add(id(start(admin, company.code(), false, false))));
                id = ids.getFirst();
                assertThat(state(awaitEnded(admin, company.code(), id))).as("state").isEqualTo("FAILED");
                answers.add(admin.client().get(filePath(id)).send());
                answers.add(admin.client().head(filePath(id)).send());
            }
            default -> {
                String doneId = id(done(admin, company.code(), false, false));
                id = doneId;
                clock.advance(Duration.ofHours(24).plusSeconds(1));
                reader = fixtures.signInAdmin();
                answers.add(reader.client().get(filePath(id)).send());
                answers.add(reader.client().head(filePath(id)).send());
                Wait.until("the export " + id + " stored as EXPIRED", () -> "EXPIRED".equals(
                        db.row("select state from " + CompanyExportSteps.TABLE + " where id::text = ?", doneId)
                                .get("state")) ? Boolean.TRUE : null);
                answers.add(reader.client().get(filePath(id)).send());
                answers.add(reader.client().head(filePath(id)).send());
            }
        }

        for (ApiResponse answer : answers) {
            assertThat(answer.status()).as("%s: %s", answer.request(), answer.snippet()).isEqualTo(404);
            if (answer.request().startsWith("GET")) {
                TalkFlow.assertRefused(answer, 404, "NOT_FOUND");
            }
        }
        assertThat(entries(reader, "COMPANY_EXPORT_DOWNLOADED", company.code())).as("download entries").isEmpty();
        assertThat(entries(reader, "COMPANY_EXPORTED", company.code())).as("export entries").hasSize(1);
    }

    @Test
    @DisplayName("S10B-AC-09 asking records one COMPANY_EXPORTED (target COMPANY and code, changes includeInternal "
            + "and includePrivate); each GET records COMPANY_EXPORT_DOWNLOADED by its admin with the export ID as "
            + "text, HEAD records nothing")
    void exportAndDownloadsAreRecorded() {
        Session first = fixtures.signInAdmin();
        Session asker = fixtures.admin(first);
        Session other = fixtures.admin(first);
        Company company = fixtures.createCompany(first);
        String id = id(done(asker, company.code(), true, false));

        other.client().head(filePath(id)).expect(200);
        List<JsonNode> afterHead = entries(first, "COMPANY_EXPORT_DOWNLOADED", company.code());
        other.client().get(filePath(id)).expect(200);
        asker.client().get(filePath(id)).expect(200);

        assertThat(afterHead).as("download entries after HEAD").isEmpty();
        assertThat(entries(first, "COMPANY_EXPORTED", company.code())).as("COMPANY_EXPORTED entries")
                .singleElement().satisfies(entry -> {
                    assertThat(entry.path("actor").path("accountId").asLong(-1)).as("actor of %s", entry)
                            .isEqualTo(asker.accountId());
                    assertTarget(entry, company.code());
                    JsonNode changes = entry.path("changes");
                    assertThat(CompanyExportSteps.fieldNames(changes)).as("changes of %s", entry)
                            .containsExactlyInAnyOrder("includeInternal", "includePrivate");
                    assertThat(changes.path("includeInternal").isBoolean() && changes.path("includeInternal")
                            .asBoolean()).as("includeInternal in %s", changes).isTrue();
                    assertThat(changes.path("includePrivate").isBoolean() && !changes.path("includePrivate")
                            .asBoolean()).as("includePrivate in %s", changes).isTrue();
                });
        List<JsonNode> downloads = entries(first, "COMPANY_EXPORT_DOWNLOADED", company.code());
        assertThat(downloads).as("download entries").extracting(e -> e.path("actor").path("accountId").asLong(-1))
                .containsExactlyInAnyOrder(other.accountId(), asker.accountId());
        for (JsonNode entry : downloads) {
            assertTarget(entry, company.code());
            JsonNode changes = entry.path("changes");
            assertThat(CompanyExportSteps.fieldNames(changes)).as("changes of %s", entry).containsExactly("exportId");
            assertThat(changes.path("exportId").isString()).as("exportId is text in %s", changes).isTrue();
            assertThat(changes.path("exportId").asString(null)).as("exportId").isEqualTo(id);
        }
    }

    private static void assertTarget(JsonNode entry, String code) {
        JsonNode target = entry.path("target");
        assertThat(CompanyExportSteps.fieldNames(target)).as("target of %s", entry)
                .containsExactlyInAnyOrder("type", "code");
        assertThat(target.path("type").asString(null)).as("target.type").isEqualTo("COMPANY");
        assertThat(target.path("code").asString(null)).as("target.code").isEqualTo(code);
    }
}
