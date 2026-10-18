package io.popdesk.privacy;

import static io.popdesk.privacy.ExportSteps.ACCOUNT_FIELDS;
import static io.popdesk.privacy.ExportSteps.ATTACHMENT_FIELDS;
import static io.popdesk.privacy.ExportSteps.COMMENT_FIELDS;
import static io.popdesk.privacy.ExportSteps.FILE_FIELDS;
import static io.popdesk.privacy.ExportSteps.ISSUE_FIELDS;
import static io.popdesk.privacy.ExportSteps.fieldNames;
import static io.popdesk.privacy.ExportSteps.time;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.ImageSteps;
import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.IssueFlow.OnBehalf;
import io.popdesk.issue.TalkFlow;
import io.popdesk.photo.PhotoSteps;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Attachment;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.File;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.trash.TrashSteps;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The account export (PII-3, S10A-AC-01 to S10A-AC-04): the answer, the fields of the file in their order, and which
 * issues, comments, and attachments it holds. No account is anonymized here, so these tests run in the bare context;
 * the export of an anonymized account is {@link AnonymizedExportTest}.
 */
class AccountExportTest extends BareAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    @Test
    @DisplayName("S10A-AC-01 an admin's export answers 200 application/json, attachment as popdesk-account-{id}.json, "
            + "no-store, with exportedAt, account, photo, issues, comments, and attachments in that order; HEAD gives the "
            + "same headers without a body")
    void answerAndFields() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        byte[] png = PhotoSteps.png(64, 48, (int) Unique.number());
        PhotoSteps.uploadPng(customer, png);
        Issue issue = fixtures.fileIssue(customer, company.code());
        Comment comment = fixtures.comment(customer, issue.key(), "Export fields " + Unique.number());
        Attachment file = fixtures.attach(customer, issue.key(), File.text("export-" + Unique.number() + ".txt",
                "Export fields file " + Unique.number()));
        JsonNode account = admin.client().get("/api/console/accounts").query("q", customer.email()).expect(200)
                .json().path("items").path(0);
        byte[] stored = PhotoSteps.photo(admin, customer.accountId()).expectStatus(200).body();

        ApiResponse head = ExportSteps.headRequest(admin, customer.accountId()).send();
        ApiResponse answer = ExportSteps.request(admin, customer.accountId()).send();

        String disposition = "attachment; filename=\"popdesk-account-" + customer.accountId() + ".json\"";
        for (ApiResponse r : List.of(head, answer)) {
            assertThat(r.status()).as("%s: %s", r, r.snippet()).isEqualTo(200);
            assertThat(r.header("Content-Type")).as("Content-Type of %s", r).startsWith("application/json");
            assertThat(r.header("Content-Disposition")).as("Content-Disposition of %s", r).isEqualTo(disposition);
            assertThat(r.headers("Cache-Control")).as("Cache-Control of %s", r)
                    .anySatisfy(v -> assertThat(v.toLowerCase(Locale.ROOT)).contains("no-store"));
        }
        assertThat(head.body()).as("body of the HEAD answer").isEmpty();
        JsonNode json = answer.json();
        assertThat(fieldNames(json)).as("fields of the file").isEqualTo(FILE_FIELDS);
        assertThat(time(json, "exportedAt")).as("exportedAt").isNotNull();

        JsonNode a = json.path("account");
        assertThat(fieldNames(a)).as("fields of account").isEqualTo(ACCOUNT_FIELDS);
        assertThat(a.path("id").asLong(-1)).as("account.id").isEqualTo(customer.accountId());
        assertThat(a.path("email").asString(null)).as("account.email").isEqualTo(customer.email());
        assertThat(a.path("name").asString(null)).as("account.name").isEqualTo(customer.name());
        assertThat(a.path("role").asString(null)).as("account.role").isEqualTo("CUSTOMER");
        assertThat(a.path("status").asString(null)).as("account.status").isEqualTo("ACTIVE");
        assertThat(a.path("locale").asString(null)).as("account.locale").isIn("en", "ko");
        assertThat(time(a, "createdAt")).as("account.createdAt").isEqualTo(time(account, "createdAt"));
        assertThat(a.path("anonymized").isBoolean() && !a.path("anonymized").booleanValue())
                .as("account.anonymized is false in %s", a).isTrue();

        JsonNode photo = json.path("photo");
        assertThat(fieldNames(photo)).as("fields of photo").containsExactlyInAnyOrder("contentType", "base64");
        assertThat(photo.path("contentType").asString(null)).as("photo.contentType").isEqualTo("image/png");
        assertThat(Base64.getDecoder().decode(photo.path("base64").asString("")))
                .as("the photo is the stored PNG").isEqualTo(stored);

        assertThat(ExportSteps.list(json, "issues")).as("issues").singleElement()
                .satisfies(i -> assertThat(fieldNames(i)).as("fields of an issue").isEqualTo(ISSUE_FIELDS));
        assertThat(fieldNames(ExportSteps.comment(json, comment.json().path("content").asString(""))))
                .as("fields of the comment").isEqualTo(COMMENT_FIELDS);
        assertThat(fieldNames(ExportSteps.attachment(json, file.json().path("fileName").asString(""))))
                .as("fields of the attachment").isEqualTo(ATTACHMENT_FIELDS);
    }

    @Test
    @DisplayName("S10A-AC-01 the photo is null for an account without one")
    void noPhotoIsNull() {
        Session admin = fixtures.signInAdmin();
        Session agent = fixtures.agent(admin);

        JsonNode json = ExportSteps.file(admin, agent.accountId());

        assertThat(json.has("photo") && json.path("photo").isNull()).as("photo is null in %s", json).isTrue();
        assertThat(json.path("account").path("role").asString(null)).as("account.role").isEqualTo("AGENT");
    }

    @Test
    @DisplayName("S10A-AC-02 issues hold what the account reported, oldest first, with key, company code, title, "
            + "content, status, private, created, closed, and trashed times: filed on its behalf too, an issue in the "
            + "trash with deletedAt, but not a purged one, a colleague's, or, for the agent who filed on its behalf, "
            + "that issue")
    void issuesOfTheReporter() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        Session colleague = fixtures.customer(admin, company.code());
        Session agent = fixtures.agent(admin, company.code());
        Issue open = fixtures.fileIssue(customer, NewIssue.in(company.code()).content("Open " + Unique.number()));
        flow.tick();
        Issue closed = fixtures.fileIssue(customer, NewIssue.in(company.code()).privateIssue());
        flow.tick();
        flow.close(customer, closed.key());
        flow.tick();
        Issue onBehalf = flow.fileOnBehalf(agent, OnBehalf.of(company.code(), customer.accountId()));
        flow.tick();
        Issue trashed = fixtures.fileIssue(customer, company.code());
        flow.tick();
        TrashSteps.delete(admin, trashed.key());
        Instant deletedAt = time(TrashSteps.entry(admin, trashed.key()), "deletedAt");
        Issue purged = fixtures.fileIssue(customer, company.code());
        TrashSteps.delete(admin, purged.key());
        TrashSteps.purge(admin, purged.key());
        Issue ofColleague = fixtures.fileIssue(colleague, company.code());

        JsonNode json = ExportSteps.file(admin, customer.accountId());
        JsonNode ofAgent = ExportSteps.file(admin, agent.accountId());

        assertThat(ExportSteps.keys(json)).as("issues of the customer, oldest first")
                .containsExactly(open.key(), closed.key(), onBehalf.key(), trashed.key());
        JsonNode o = ExportSteps.issue(json, open.key());
        JsonNode detail = flow.detail(admin, open.key());
        assertThat(o.path("companyCode").asString(null)).as("companyCode").isEqualTo(company.code());
        assertThat(o.path("title").asString(null)).as("title").isEqualTo(detail.path("title").asString(""));
        assertThat(o.path("content").asString(null)).as("content").isEqualTo(detail.path("content").asString(""));
        assertThat(o.path("status").asString(null)).as("status").isEqualTo("RECEIVED");
        assertThat(o.path("private").isBoolean() && !o.path("private").booleanValue()).as("private of %s", o).isTrue();
        assertThat(time(o, "createdAt")).as("createdAt").isEqualTo(time(detail, "createdAt"));
        assertThat(time(o, "closedAt")).as("closedAt of an open issue").isNull();
        assertThat(time(o, "deletedAt")).as("deletedAt of a live issue").isNull();

        JsonNode c = ExportSteps.issue(json, closed.key());
        assertThat(c.path("status").asString(null)).as("status of the closed issue").isEqualTo("CLOSED");
        assertThat(c.path("private").booleanValue()).as("private of %s", c).isTrue();
        assertThat(time(c, "closedAt")).as("closedAt").isNotNull()
                .isEqualTo(time(flow.detail(admin, closed.key()), "closedAt"));
        assertThat(time(ExportSteps.issue(json, trashed.key()), "deletedAt")).as("deletedAt of the trashed issue")
                .isEqualTo(deletedAt);
        assertThat(ExportSteps.keys(json)).as("issues of the customer")
                .doesNotContain(purged.key(), ofColleague.key());
        assertThat(ExportSteps.keys(ofAgent)).as("issues of the agent who filed one on the customer's behalf")
                .doesNotContain(onBehalf.key());
    }

    @Test
    @DisplayName("S10A-AC-03 comments hold what the account wrote and did not delete, with issue key, content, "
            + "internal, created and edited times; staff's internal comments too; not a comment on a purged issue")
    void commentsOfTheWriter() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        Session agent = fixtures.agent(admin, company.code());
        Issue issue = fixtures.fileIssue(customer, company.code());
        Issue purged = fixtures.fileIssue(customer, company.code());
        Comment kept = fixtures.comment(customer, issue.key(), "Kept " + Unique.number());
        flow.tick();
        String editedText = "Edited " + Unique.number();
        Comment edited = fixtures.comment(customer, issue.key(), "Before the edit " + Unique.number());
        TalkFlow.editContent(customer, issue.key(), edited.id(), editedText);
        Comment deleted = fixtures.comment(customer, issue.key(), "Deleted " + Unique.number());
        TalkFlow.delete(customer, issue.key(), deleted.id());
        Comment onPurged = fixtures.comment(customer, purged.key(), "On a purged issue " + Unique.number());
        TrashSteps.delete(admin, purged.key());
        TrashSteps.purge(admin, purged.key());
        Comment answer = fixtures.comment(agent, issue.key(), "Answer " + Unique.number());
        Comment note = fixtures.internalComment(agent, issue.key(), "Internal note " + Unique.number());

        JsonNode json = ExportSteps.file(admin, customer.accountId());
        JsonNode ofAgent = ExportSteps.file(admin, agent.accountId());

        String keptText = kept.json().path("content").asString("");
        assertThat(ExportSteps.contents(json)).as("comments of the customer, oldest first")
                .containsExactly(keptText, editedText);
        JsonNode k = ExportSteps.comment(json, keptText);
        JsonNode keptNow = TalkFlow.comment(customer, issue.key(), kept.id());
        assertThat(k.path("issueKey").asString(null)).as("issueKey").isEqualTo(issue.key());
        assertThat(k.path("internal").isBoolean() && !k.path("internal").booleanValue()).as("internal of %s", k)
                .isTrue();
        assertThat(time(k, "createdAt")).as("createdAt").isEqualTo(time(keptNow, "createdAt"));
        assertThat(time(k, "editedAt")).as("editedAt of a comment never edited").isNull();
        JsonNode e = ExportSteps.comment(json, editedText);
        assertThat(time(e, "editedAt")).as("editedAt of the edited comment")
                .isEqualTo(time(TalkFlow.comment(customer, issue.key(), edited.id()), "editedAt")).isNotNull();
        assertThat(json.toString()).as("the file")
                .doesNotContain(deleted.json().path("content").asString("?"))
                .doesNotContain(onPurged.json().path("content").asString("?"))
                .doesNotContain(edited.json().path("content").asString("?"));

        assertThat(ExportSteps.contents(ofAgent)).as("comments of the agent").containsExactly(
                answer.json().path("content").asString(""), note.json().path("content").asString(""));
        assertThat(ExportSteps.comment(ofAgent, note.json().path("content").asString("")).path("internal")
                .booleanValue()).as("internal of the agent's note").isTrue();
    }

    @Test
    @DisplayName("S10A-AC-04 attachments hold the files the account uploaded and did not delete, with issue key, "
            + "comment ID or null, file name, size, and upload time, without their content; not a deleted one, not "
            + "someone else's, not a temporary image that is attached to nothing yet")
    void attachmentsOfTheUploader() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        Session agent = fixtures.agent(admin, company.code());
        Issue issue = fixtures.fileIssue(customer, company.code());
        String secret = "Attachment content " + Unique.number();
        Attachment onIssue = fixtures.attach(customer, issue.key(), File.text("issue-" + Unique.number() + ".txt",
                secret));
        flow.tick();
        Comment comment = fixtures.comment(customer, issue.key(), "With a file " + Unique.number());
        Attachment onComment = fixtures.attachToComment(customer, issue.key(), comment.id(),
                File.text("comment-" + Unique.number() + ".txt", "On a comment " + Unique.number()));
        Attachment removed = fixtures.attach(customer, issue.key(), File.text("removed-" + Unique.number() + ".txt",
                "Removed " + Unique.number()));
        customer.client().delete("/api/portal/issues/" + issue.key() + "/attachments/" + removed.id()).expect(204);
        Attachment ofAgent = fixtures.attach(agent, issue.key(), File.text("agent-" + Unique.number() + ".txt",
                "Of the agent " + Unique.number()));
        String imageName = "temporary-" + Unique.number() + ".png";
        ImageSteps.uploaded(customer, imageName, ImageSteps.png());

        ApiResponse answer = ExportSteps.export(admin, customer.accountId());

        JsonNode json = answer.json();
        String issueFile = onIssue.json().path("fileName").asString("");
        String commentFile = onComment.json().path("fileName").asString("");
        assertThat(ExportSteps.fileNames(json)).as("attachments of the customer, oldest first")
                .containsExactly(issueFile, commentFile);
        JsonNode a = ExportSteps.attachment(json, issueFile);
        assertThat(a.path("issueKey").asString(null)).as("issueKey").isEqualTo(issue.key());
        assertThat(a.has("commentId") && a.path("commentId").isNull()).as("commentId of a file on the issue in %s", a)
                .isTrue();
        assertThat(a.path("size").asLong(-1)).as("size").isEqualTo(secret.getBytes(StandardCharsets.UTF_8).length);
        assertThat(time(a, "createdAt")).as("createdAt").isEqualTo(time(onIssue.json(), "createdAt"));
        assertThat(ExportSteps.attachment(json, commentFile).path("commentId").asLong(-1)).as("commentId")
                .isEqualTo(comment.id());
        assertThat(answer.text()).as("the file")
                .doesNotContain(secret)
                .doesNotContain(Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)))
                .doesNotContain(removed.json().path("fileName").asString("?"))
                .doesNotContain(ofAgent.json().path("fileName").asString("?"))
                .doesNotContain(imageName);
    }
}
