package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.deploy.AppSteps.Person;
import io.popdesk.support.ApiResponse;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * What the sample generator of this version ({@code deploy/upgrade/make-sample.py}) leaves of slice 12 (S12-AC-41,
 * {@code design.md} 1절): a received email that became a comment, a discarded one, and a sent email about an issue,
 * written with SQL because the API cannot make them without an IMAP server. No HTTP view shows these records, so the
 * test reads them from the database of the installation started on the sample; the tables and columns are the ones
 * of {@code design.md} 1절. That every business table of the sample has a row is {@code UpgradeHarnessTest}'s check.
 */
@Tag("deploy")
class MailInSampleTest {

    private final String project = "pdt-mailin-sample-" + UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void cleanUp() {
        IssueInstallSteps.removeProject(project);
    }

    @Test
    @DisplayName("S12-AC-41 an installation started on the sample of this version has a received email that became a "
            + "comment of its issue, a discarded one without a sender address, and a sent email about an existing "
            + "issue, and its system status reads with mail-in off")
    void sampleHasMailRecords() {
        Path sample = IssueInstallSteps.makeSample(project);

        try (Stack stack = IssueInstallSteps.startedOn("mailinsample", sample.resolve("sample.dump"))) {
            Db db = stack.db();
            String schema = Db.quote(db.schema());
            assertThat(db.count("select count(*) from " + schema + ".mail_received r join " + schema
                    + ".comment c on c.id = r.comment_id and c.issue_id = r.issue_id "
                    + "where r.outcome = 'COMMENT' and r.decided_at is not null and r.sender_account_id is not null"))
                    .as("received emails that became a comment of their issue").isPositive();
            assertThat(db.count("select count(*) from " + schema + ".mail_received where outcome = 'DISCARDED' "
                    + "and decided_at is not null and reason is not null and sender_address is null "
                    + "and issue_id is null and comment_id is null"))
                    .as("discarded received emails, without a sender address").isPositive();
            assertThat(db.count("select count(*) from " + schema + ".mail_sent s join " + schema
                    + ".issue i on i.id = s.issue_id"))
                    .as("sent emails about an existing issue").isPositive();

            Person admin = IssueInstallSteps.sampleAdmin(stack);
            ApiResponse status = admin.client().get("/api/console/system/status").expect(200);
            JsonNode mailIn = status.json().path("mailIn");
            assertThat(mailIn.path("enabled").isBoolean()).as("mailIn.enabled in %s", status.json()).isTrue();
            assertThat(mailIn.path("enabled").booleanValue()).as("mailIn.enabled in %s", status.json()).isFalse();
            assertThat(mailIn.path("offReason").asString(null)).as("mailIn.offReason in %s", status.json())
                    .isEqualTo("NOT_SET");
        }
    }
}
