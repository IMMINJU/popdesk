package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.deploy.AppSteps.Person;
import io.popdesk.manager.ManagerSteps;
import io.popdesk.support.ApiResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * What the sample generator of this version ({@code deploy/upgrade/make-sample.py}) leaves of slice 10b
 * (S10B-AC-29): a company export that ended and whose file is gone, and an issue that automatic cleanup moved to the
 * trash. The installation started on that sample lists the export as {@code EXPIRED} on its company (C-9) and the
 * issue in the trash without a person who deleted it.
 */
@Tag("deploy")
class ExportRetentionSampleTest {

    private final String project = "pdt-export-sample-" + UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void cleanUp() {
        IssueInstallSteps.removeProject(project);
    }

    @Test
    @DisplayName("S10B-AC-29 an installation started on the sample of this version lists an EXPIRED company export "
            + "on its company, whose file is 404, and an issue in the trash with deletedBy null (automatic cleanup)")
    void sampleHasAnExpiredExportAndACleanedUpIssue() {
        Path sample = IssueInstallSteps.makeSample(project);

        try (Stack stack = IssueInstallSteps.startedOn("exportsample", sample.resolve("sample.dump"))) {
            Person admin = IssueInstallSteps.sampleAdmin(stack);
            List<JsonNode> expired = new ArrayList<>();
            for (JsonNode company : ManagerSteps.pages(admin.client(), "/api/console/companies")) {
                ApiResponse exports = admin.client()
                        .get("/api/console/companies/" + company.path("code").asString("") + "/exports").expect(200);
                for (JsonNode export : exports.json()) {
                    if ("EXPIRED".equals(export.path("state").asString(null))) {
                        expired.add(export);
                    }
                }
            }

            assertThat(expired).as("EXPIRED company exports of the sample").isNotEmpty();
            JsonNode export = expired.getFirst();
            assertThat(export.path("finishedAt").isString()).as("finishedAt of %s", export).isTrue();
            assertThat(export.path("requestedBy").path("accountId").asLong(-1)).as("requestedBy of %s", export)
                    .isPositive();
            ApiResponse file = admin.client()
                    .get("/api/console/company-exports/" + export.path("id").asString("") + "/file").send();
            assertThat(file.status()).as("the file of %s: %s", export, file.snippet()).isEqualTo(404);

            List<JsonNode> byCleanup = ManagerSteps.pages(admin.client(), "/api/console/trash").stream()
                    .filter(e -> e.has("deletedBy") && e.path("deletedBy").isNull()).toList();
            assertThat(byCleanup).as("issues in the trash of the sample that automatic cleanup moved").isNotEmpty();
            assertThat(byCleanup).as("status of the issues automatic cleanup moved")
                    .allSatisfy(e -> assertThat(e.path("status").asString(null)).isEqualTo("CLOSED"));
        }
    }
}
