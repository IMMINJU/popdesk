package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.bytes;
import static io.popdesk.attachment.AttachmentSteps.download;
import static io.popdesk.attachment.AttachmentSteps.headersWithoutDate;
import static io.popdesk.attachment.AttachmentSteps.ids;
import static io.popdesk.attachment.AttachmentSteps.list;
import static io.popdesk.attachment.AttachmentSteps.path;
import static io.popdesk.attachment.AttachmentSteps.upload;
import static io.popdesk.attachment.AttachmentSteps.uploadToComment;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.AttachmentSteps.Scene;
import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import java.util.UUID;
import java.util.function.BiFunction;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One 404 for attachments (conventions "Attachment paths"): for every case the criteria list, every
 * attachment endpoint (download, range request, {@code HEAD}, delete) answers exactly as it does for
 * an ID that does not exist, on an issue the caller can see. Status, headers but {@code Date}, and
 * body bytes are compared, request by request, on the same client.
 *
 * <p>{@code pending} and {@code purging} attachments are not here: no API call leaves an attachment
 * in either state for long enough to ask for it (see the report).
 */
class AttachmentNotFoundTest extends BareAppTest {

    private AttachmentSteps steps;

    @BeforeEach
    void setUp() {
        steps = new AttachmentSteps(fixtures);
    }

    @Test
    @DisplayName("S0-AC-102 an ID that does not exist is 404 NOT_FOUND on every attachment endpoint")
    void unknownIdIsNotFound() {
        Scene s = steps.scene();
        Issue own = fixtures.fileIssue(s.customer(), s.code());
        String path = path(s.customer(), own.key(), UUID.randomUUID().toString());

        ApiResponse get = s.customer().client().get(path).send();
        ApiResponse head = s.customer().client().head(path).send();
        ApiResponse delete = s.customer().client().delete(path).send();

        assertThat(get.status()).isEqualTo(404);
        assertThat(get.errorCode()).isEqualTo("NOT_FOUND");
        assertThat(head.status()).isEqualTo(404);
        assertThat(delete.status()).isEqualTo(404);
        assertThat(delete.errorCode()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("S0-AC-102 an attachment of another company's issue answers like an unknown ID, and stays")
    void otherCompanysIssue() {
        Scene s = steps.scene();
        Company other = fixtures.createCompany(s.admin());
        Session outsider = fixtures.customer(s.admin(), other.code());
        Issue own = fixtures.fileIssue(outsider, other.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        String id = upload(s.customer(), issue.key(), "secret.bin", bytes(100, 1)).expectStatus(201).jsonText("id");

        assertSameAsUnknownId(outsider, own.key(), issue.key(), id);

        assertThat(download(s.customer(), issue.key(), id).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("S0-AC-102 an attachment of a colleague's private issue answers like an unknown ID, and stays")
    void colleaguesPrivateIssue() {
        Scene s = steps.scene();
        Session colleague = fixtures.customer(s.admin(), s.code());
        Issue own = fixtures.fileIssue(colleague, s.code());
        Issue hidden = fixtures.fileIssue(s.customer(), NewIssue.in(s.code()).privateIssue());
        String id = upload(s.customer(), hidden.key(), "private.bin", bytes(100, 2)).expectStatus(201).jsonText("id");

        assertSameAsUnknownId(colleague, own.key(), hidden.key(), id);

        assertThat(download(s.customer(), hidden.key(), id).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("S0-AC-102 an attachment asked for under another issue the caller can see answers like an unknown ID, and stays")
    void attachmentOfAnotherIssue() {
        Scene s = steps.scene();
        Issue first = fixtures.fileIssue(s.customer(), s.code());
        Issue second = fixtures.fileIssue(s.customer(), s.code());
        String id = upload(s.customer(), first.key(), "first.bin", bytes(100, 3)).expectStatus(201).jsonText("id");

        assertSameAsUnknownId(s.customer(), second.key(), second.key(), id);

        assertThat(download(s.customer(), first.key(), id).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("S0-AC-60, S0-AC-102 a customer asking for an attachment of an internal comment gets the unknown-ID 404 everywhere, and it stays")
    void internalAttachmentForCustomer() {
        Scene s = steps.scene();
        Session agent = fixtures.agent(s.admin(), s.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        Comment internal = fixtures.internalComment(agent, issue.key(), "Internal note");
        String id = uploadToComment(agent, issue.key(), internal.id(), "internal.bin", bytes(100, 4))
                .expectStatus(201).jsonText("id");

        assertSameAsUnknownId(s.customer(), issue.key(), issue.key(), id);

        assertThat(download(agent, issue.key(), id).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("S0-AC-60 the customer's attachment list leaves out internal attachments; staff see them marked internal")
    void internalAttachmentNotListed() {
        Scene s = steps.scene();
        Session agent = fixtures.agent(s.admin(), s.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        String visible = upload(s.customer(), issue.key(), "public.bin", bytes(100, 5)).expectStatus(201)
                .jsonText("id");
        Comment publicAnswer = fixtures.comment(agent, issue.key(), "Public answer");
        String onPublic = uploadToComment(agent, issue.key(), publicAnswer.id(), "answer.bin", bytes(100, 6))
                .expectStatus(201).jsonText("id");
        Comment internal = fixtures.internalComment(agent, issue.key(), "Internal note");
        String hidden = uploadToComment(agent, issue.key(), internal.id(), "internal.bin", bytes(100, 7))
                .expectStatus(201).jsonText("id");

        ApiResponse page = s.customer().client().get(s.customer().api("/issues/" + issue.key() + "/attachments"))
                .query("size", 100).expect(200);

        assertThat(ids(list(s.customer(), issue.key()))).containsExactlyInAnyOrder(visible, onPublic);
        assertThat(page.json().path("totalElements").asLong()).isEqualTo(2);
        assertThat(page.text()).doesNotContain(hidden).doesNotContain("internal.bin");
        assertThat(list(agent, issue.key())).filteredOn(n -> hidden.equals(n.path("id").stringValue()))
                .singleElement().satisfies(n -> assertThat(n.path("internal").booleanValue()).isTrue());
    }

    @Test
    @DisplayName("S0-AC-102 a deleted attachment answers like an unknown ID on every endpoint")
    void purgedAttachment() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        String id = upload(s.customer(), issue.key(), "gone.bin", bytes(100, 8)).expectStatus(201).jsonText("id");
        s.customer().client().delete(path(s.customer(), issue.key(), id)).expect(204);

        assertSameAsUnknownId(s.customer(), issue.key(), issue.key(), id);
    }

    @Test
    @DisplayName("S0-AC-102 on the console, an agent not assigned to the company gets the unknown-ID 404 for its attachments, and they stay")
    void unassignedAgent() {
        Scene s = steps.scene();
        Company other = fixtures.createCompany(s.admin());
        Session outsider = fixtures.agent(s.admin(), other.code());
        Session otherCustomer = fixtures.customer(s.admin(), other.code());
        Issue own = fixtures.fileIssue(otherCustomer, other.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        String id = upload(s.customer(), issue.key(), "console.bin", bytes(100, 9)).expectStatus(201).jsonText("id");

        assertSameAsUnknownId(outsider, own.key(), issue.key(), id);

        assertThat(download(s.customer(), issue.key(), id).status()).isEqualTo(200);
    }

    /**
     * For download, range request, {@code HEAD}, and delete (in that order, so delete comes last),
     * {@code caller}'s request for {@code id} under {@code key} answers 404 with the same headers
     * (but {@code Date}) and the same body as its request for a random ID under {@code ownKey}.
     */
    private static void assertSameAsUnknownId(Session caller, String ownKey, String key, String id) {
        String unknown = path(caller, ownKey, UUID.randomUUID().toString());
        String target = path(caller, key, id);
        SoftAssertions softly = new SoftAssertions();
        compare(softly, "download", unknown, target, (p, c) -> c.client().get(p), caller);
        compare(softly, "range", unknown, target, (p, c) -> c.client().get(p).header("Range", "bytes=0-9"), caller);
        compare(softly, "HEAD", unknown, target, (p, c) -> c.client().head(p), caller);
        compare(softly, "delete", unknown, target, (p, c) -> c.client().delete(p), caller);
        softly.assertAll();
    }

    private static void compare(SoftAssertions softly, String what, String unknown, String target,
            BiFunction<String, Session, ApiRequest> request, Session caller) {
        ApiResponse expected = request.apply(unknown, caller).send();
        ApiResponse actual = request.apply(target, caller).send();
        softly.assertThat(expected.status()).as(what + " of an unknown ID").isEqualTo(404);
        softly.assertThat(actual.status()).as(what + " status").isEqualTo(expected.status());
        softly.assertThat(headersWithoutDate(actual)).as(what + " headers").isEqualTo(headersWithoutDate(expected));
        softly.assertThat(actual.body()).as(what + " body").isEqualTo(expected.body());
    }
}
