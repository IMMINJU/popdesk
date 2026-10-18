package io.popdesk.privacy;

import static io.popdesk.issue.TalkFlow.isNull;
import static io.popdesk.privacy.CompanyExportSteps.awaitEnded;
import static io.popdesk.privacy.CompanyExportSteps.exportFiles;
import static io.popdesk.privacy.CompanyExportSteps.filePath;
import static io.popdesk.privacy.CompanyExportSteps.id;
import static io.popdesk.privacy.CompanyExportSteps.start;
import static io.popdesk.privacy.CompanyExportSteps.state;
import static io.popdesk.privacy.CompanyExportSteps.withExportDirAFile;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.TalkFlow;
import io.popdesk.support.Fixtures.App;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.HeldWrite;
import io.popdesk.support.Wait;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The inbox items of an export's end (S10B-AC-04, S10B-AC-05): {@code COMPANY_EXPORT_READY} to the admin who asked
 * and to nobody else, none for an admin suspended or anonymized meanwhile, and {@code COMPANY_EXPORT_FAILED} to every admin when the
 * ZIP cannot be written.
 */
class CompanyExportNotifyTest extends CompanyExportAppTest {

    private static final String READY = "COMPANY_EXPORT_READY";
    private static final String FAILED = "COMPANY_EXPORT_FAILED";

    /** The caller's console inbox items of this kind about this company. */
    private static List<JsonNode> items(Session admin, String kind, String code) {
        return TalkFlow.inbox(admin).stream()
                .filter(n -> kind.equals(TalkFlow.kind(n)) && code.equals(n.path("companyCode").asString(null)))
                .toList();
    }

    /** Waits for the caller's one item of this kind about the company and checks it is about no issue. */
    private static JsonNode awaitItem(Session admin, String kind, String code) {
        List<JsonNode> found = Wait.until(kind + " about " + code + " in the inbox of " + admin.email(), () -> {
            List<JsonNode> now = items(admin, kind, code);
            return now.isEmpty() ? null : now;
        });
        assertThat(found).as("%s items about %s of %s", kind, code, admin.email()).hasSize(1);
        assertThat(isNull(found.getFirst(), "issue")).as("issue of %s is null", found.getFirst()).isTrue();
        return found.getFirst();
    }

    @Test
    @DisplayName("S10B-AC-04 when the ZIP is ready the admin who asked gets COMPANY_EXPORT_READY with the company "
            + "code, and another admin gets none")
    void readyGoesToTheAdminWhoAsked() {
        Session first = fixtures.signInAdmin();
        Session asker = fixtures.admin(first);
        Session other = fixtures.admin(first);
        Company company = fixtures.createCompany(first);

        String id = id(start(asker, company.code(), false, false));

        assertThat(state(awaitEnded(asker, company.code(), id))).as("state at the end").isEqualTo("DONE");
        awaitItem(asker, READY, company.code());
        assertThat(items(other, READY, company.code())).as("READY items of another admin").isEmpty();
        assertThat(items(first, READY, company.code())).as("READY items of the first admin").isEmpty();
        assertThat(items(asker, FAILED, company.code())).as("FAILED items of the admin who asked").isEmpty();
    }

    @Test
    @DisplayName("S10B-AC-04 an admin suspended while the export ran gets no COMPANY_EXPORT_READY, also once active "
            + "again; the admin who asks next gets it")
    void noReadyForAnAdminSuspendedMeanwhile() {
        Session first = fixtures.signInAdmin();
        Session asker = fixtures.admin(first);
        Company company = fixtures.createCompany(first);
        String id;

        try (HeldWrite end = holdEnd()) {
            id = id(start(asker, company.code(), false, false));
            try {
                flow.suspend(first, asker.accountId());
            } finally {
                end.release();
            }
        }
        assertThat(state(awaitEnded(first, company.code(), id))).as("state at the end").isEqualTo("DONE");
        flow.reactivate(first, asker.accountId());
        Session askerAgain = fixtures.signIn(App.CONSOLE, asker.email(), asker.password());
        String next = id(start(first, company.code(), false, false));
        awaitEnded(first, company.code(), next);

        awaitItem(first, READY, company.code());
        assertThat(items(askerAgain, READY, company.code())).as("READY items of the admin suspended meanwhile")
                .isEmpty();
    }

    @Test
    @DisplayName("S10B-AC-04 an admin anonymized while the export ran gets no COMPANY_EXPORT_READY (no inbox row at "
            + "all); the admin who asks next gets it")
    void noReadyForAnAdminAnonymizedMeanwhile() {
        Session first = fixtures.signInAdmin();
        // Another admin besides the one who asks, so anonymizing it is not refused as the last admin.
        fixtures.admin(first);
        Session asker = fixtures.admin(first);
        Company company = fixtures.createCompany(first);
        String id;

        try (HeldWrite end = holdEnd()) {
            id = id(start(asker, company.code(), false, false));
            try {
                PrivacySteps.anonymize(first, asker.accountId());
            } finally {
                end.release();
            }
        }
        assertThat(state(awaitEnded(first, company.code(), id))).as("state at the end").isEqualTo("DONE");
        String next = id(start(first, company.code(), false, false));
        awaitEnded(first, company.code(), next);

        awaitItem(first, READY, company.code());
        assertThat(db.count("select count(*) from notification where account_id = ?", asker.accountId()))
                .as("inbox rows of the admin anonymized meanwhile").isZero();
    }

    @Test
    @DisplayName("S10B-AC-05 when the ZIP cannot be written the export becomes FAILED with DISK, leaves no file, its "
            + "download is 404, and every admin gets COMPANY_EXPORT_FAILED with the company code")
    void failureGoesToEveryAdmin() {
        Session first = fixtures.signInAdmin();
        Session asker = fixtures.admin(first);
        Session other = fixtures.admin(first);
        Company company = fixtures.createCompany(first);
        fixtures.fileIssue(fixtures.customer(first, company.code()), company.code());
        AtomicReference<JsonNode> ended = new AtomicReference<>();

        withExportDirAFile(() -> {
            String id = id(start(asker, company.code(), false, false));
            ended.set(awaitEnded(asker, company.code(), id));
        });
        JsonNode export = ended.get();
        String id = id(export);

        assertThat(state(export)).as("state of %s", export).isEqualTo("FAILED");
        assertThat(export.path("failure").asString(null)).as("failure of %s", export).isEqualTo("DISK");
        assertThat(isNull(export, "finishedAt")).as("finishedAt of %s is set", export).isFalse();
        assertThat(isNull(export, "size")).as("size of %s is null", export).isTrue();
        assertThat(exportFiles()).as("files in the export folder").noneMatch(name -> name.contains(id));
        assertThat(asker.client().get(filePath(id)).send().status()).as("download of the failed export")
                .isEqualTo(404);
        for (Session admin : List.of(asker, other, first)) {
            awaitItem(admin, FAILED, company.code());
            assertThat(items(admin, READY, company.code())).as("READY items of %s", admin.email()).isEmpty();
        }
    }
}
