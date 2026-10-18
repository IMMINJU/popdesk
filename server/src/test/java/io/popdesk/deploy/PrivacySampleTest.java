package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.deploy.AppSteps.Person;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.AuditChainMath;
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
 * What the sample generator of this version ({@code deploy/upgrade/make-sample.py}) leaves of slice 10a (S10A-AC-34,
 * {@code design.md} 1절): an anonymized account that had issues, comments, and audit entries. The installation
 * started on that sample lists it as anonymized among the members, shows its issues with the anonymous name, and its
 * integrity check says the chain matches.
 */
@Tag("deploy")
class PrivacySampleTest {

    /** The anonymous name in the two installation languages ({@code acceptance.md} 2절). */
    private static final List<String> ANONYMOUS_NAMES = List.of("Deleted user", "삭제된 사용자");

    private final String project = "pdt-privacy-sample-" + UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void cleanUp() {
        IssueInstallSteps.removeProject(project);
    }

    @Test
    @DisplayName("S10A-AC-34 an installation started on the sample of this version lists an anonymized account "
            + "(deleted-{id}@invalid, anonymized true, the anonymous name) whose issues show the anonymous name as "
            + "reporter, and its integrity check says the chain matches")
    void sampleHasAnAnonymizedAccount() {
        Path sample = IssueInstallSteps.makeSample(project);

        try (Stack stack = IssueInstallSteps.startedOn("privacysample", sample.resolve("sample.dump"))) {
            Person admin = IssueInstallSteps.sampleAdmin(stack);
            List<JsonNode> anonymized = accounts(admin).stream()
                    .filter(a -> a.path("anonymized").asBoolean(false)).toList();

            assertThat(anonymized).as("anonymized accounts of the sample").isNotEmpty();
            JsonNode withIssues = null;
            for (JsonNode account : anonymized) {
                long id = account.path("id").asLong(-1);
                assertThat(account.path("email").asString(null)).as("email of %s", account)
                        .isEqualTo("deleted-" + id + "@invalid");
                assertThat(account.path("name").asString(null)).as("name of %s", account).isIn(ANONYMOUS_NAMES);
                assertThat(account.path("status").asString(null)).as("status of %s", account).isEqualTo("SUSPENDED");
                ApiResponse issues = admin.client().get("/api/console/issues").query("reporter", id)
                        .query("size", 100).expect(200);
                if (!IssueInstallSteps.items(issues).isEmpty()) {
                    withIssues = account;
                    for (JsonNode issue : IssueInstallSteps.items(issues)) {
                        assertThat(issue.path("reporter").path("name").asString(null)).as("reporter of %s", issue)
                                .isEqualTo(account.path("name").asString(""));
                    }
                }
            }
            assertThat(withIssues).as("an anonymized account with issues").isNotNull();
            AuditChainMath.assertValid(admin.client());
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
