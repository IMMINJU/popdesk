package io.popdesk.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.photo.PhotoSteps;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Attachment;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.File;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The export of an anonymized account (S10A-AC-07): the anonymous values, and what it wrote in the same form. */
class AnonymizedExportTest extends PrivacyAppTest {

    @Test
    @DisplayName("S10A-AC-07 the export of an anonymized customer gives the anonymous name and email, anonymized "
            + "true, photo null, and the issues, comments, and attachments it wrote; the old name and email are gone")
    void anonymousValuesAndWhatItWrote() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        PhotoSteps.uploadPng(customer, PhotoSteps.png(32, 32, (int) Unique.number()));
        Issue issue = fixtures.fileIssue(customer, company.code());
        Comment comment = fixtures.comment(customer, issue.key(), "Before anonymizing " + Unique.number());
        Attachment file = fixtures.attach(customer, issue.key(), File.text("anon-" + Unique.number() + ".txt",
                "Anonymized file"));
        PrivacySteps.anonymize(admin, customer.accountId());

        ApiResponse answer = ExportSteps.export(admin, customer.accountId());

        JsonNode json = answer.json();
        JsonNode account = json.path("account");
        assertThat(account.path("id").asLong(-1)).as("account.id").isEqualTo(customer.accountId());
        assertThat(account.path("email").asString(null)).as("account.email")
                .isEqualTo(PrivacySteps.anonymousEmail(customer.accountId()));
        assertThat(account.path("name").asString(null)).as("account.name").isEqualTo(ANONYMOUS_NAME);
        assertThat(account.path("status").asString(null)).as("account.status").isEqualTo("SUSPENDED");
        assertThat(account.path("anonymized").asBoolean(false)).as("account.anonymized").isTrue();
        assertThat(json.has("photo") && json.path("photo").isNull()).as("photo is null in %s", json).isTrue();
        assertThat(ExportSteps.keys(json)).as("issues").containsExactly(issue.key());
        assertThat(ExportSteps.contents(json)).as("comments")
                .containsExactly(comment.json().path("content").asString(""));
        assertThat(ExportSteps.fileNames(json)).as("attachments")
                .containsExactly(file.json().path("fileName").asString(""));
        assertThat(answer.text()).as("the file").doesNotContain(customer.email()).doesNotContain(customer.name());
    }
}
