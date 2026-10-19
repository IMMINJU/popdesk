package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.deploy.AppSteps.Person;
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
 * What the sample generator of this version ({@code deploy/upgrade/make-sample.py}) leaves of slice 11 (S11-AC-36,
 * {@code design.md} 1절): an OIDC link and a sign-in request, written with SQL because the API cannot make them
 * without a provider. The installation started on that sample lists the linked account with {@code oidcLinked} true.
 * That every business table of the sample has a row is {@code UpgradeHarnessTest}'s check.
 */
@Tag("deploy")
class OidcSampleTest {

    private final String project = "pdt-oidc-sample-" + UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void cleanUp() {
        IssueInstallSteps.removeProject(project);
    }

    @Test
    @DisplayName("S11-AC-36 an installation started on the sample of this version lists an account that is not "
            + "anonymized with oidcLinked true, and every other account has oidcLinked as a boolean")
    void sampleHasALinkedAccount() {
        Path sample = IssueInstallSteps.makeSample(project);

        try (Stack stack = IssueInstallSteps.startedOn("oidcsample", sample.resolve("sample.dump"))) {
            Person admin = IssueInstallSteps.sampleAdmin(stack);
            List<JsonNode> accounts = accounts(admin);

            assertThat(accounts).as("accounts of the sample").isNotEmpty();
            for (JsonNode account : accounts) {
                assertThat(account.path("oidcLinked").isBoolean()).as("oidcLinked of %s", account).isTrue();
            }
            List<JsonNode> linked = accounts.stream().filter(a -> a.path("oidcLinked").booleanValue())
                    .filter(a -> !a.path("anonymized").asBoolean(true)).toList();
            assertThat(linked).as("linked accounts of the sample that are not anonymized").isNotEmpty();
        }
    }

    /** Every account the console lists, page by page. */
    private static List<JsonNode> accounts(Person admin) {
        List<JsonNode> out = new ArrayList<>();
        for (int page = 1; page <= 100; page++) {
            ApiResponse response = admin.client().get("/api/console/accounts").query("page", page)
                    .query("size", 100).expect(200);
            out.addAll(IssueInstallSteps.items(response));
            if (page >= response.json().path("totalPages").asInt(0)) {
                return out;
            }
        }
        throw new AssertionError("More than 100 pages of accounts");
    }
}
