package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.bytes;
import static io.popdesk.attachment.AttachmentSteps.download;
import static io.popdesk.attachment.AttachmentSteps.entry;
import static io.popdesk.attachment.AttachmentSteps.path;
import static io.popdesk.attachment.AttachmentSteps.text;
import static io.popdesk.attachment.ImageSteps.assertAttachmentLikeUnknown;
import static io.popdesk.attachment.ImageSteps.assertReadsLikeUnknown;
import static io.popdesk.attachment.ImageSteps.attachmentHistory;
import static io.popdesk.attachment.ImageSteps.commentWith;
import static io.popdesk.attachment.ImageSteps.inlineIds;
import static io.popdesk.attachment.ImageSteps.png;
import static io.popdesk.attachment.ImageSteps.read;
import static io.popdesk.attachment.ImageSteps.sha256;
import static io.popdesk.attachment.ImageSteps.uploaded;
import static io.popdesk.issue.TalkFlow.body;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.ImageSteps.Place;
import io.popdesk.attachment.ImageSteps.Stage;
import io.popdesk.attachment.ImageSteps.Write;
import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * Attaching temporary images with the request that saves an issue or a comment ({@code imageIds}),
 * and what an attached image is afterwards: an attachment of that issue, marked {@code inline}.
 * Refusals are in {@link ImageBindRefusalTest}, limits and closed issues in {@link ImageBindLimitTest}.
 */
class ImageBindTest extends BareAppTest {

    @ParameterizedTest(name = "{0}")
    @EnumSource(Write.class)
    @DisplayName("S5-AC-20, S5-AC-24 every request that saves an issue or a comment attaches the images it names: "
            + "listed as inline on that issue (and comment), read under the issue with the same bytes, and gone from "
            + "the temporary path")
    void everyWriteAttachesItsImages(Write write) {
        Stage st = ImageSteps.stage(fixtures);
        Session writer = write.writer(st);
        byte[] content = png();
        String name = "shot-" + Unique.number() + ".png";
        String id = uploaded(writer, name, content);

        ApiResponse saved = write.request(st, List.of(id)).send();

        assertThat(saved.status()).as("%s: %s", saved.request(), saved.snippet()).isEqualTo(write.success());
        assertThat(write.saved(st)).as("the text of %s is stored", write).isTrue();
        Place place = write.place(st, saved);
        JsonNode listed = entry(writer, place.issueKey(), id);
        assertThat(listed).as("the image in the attachment list of %s", place.issueKey()).isNotNull();
        assertThat(listed.path("inline").isBoolean() && listed.path("inline").asBoolean()).as("inline in %s", listed)
                .isTrue();
        assertThat(listed.path("commentId").isNull() ? null : (Long) listed.path("commentId").asLong())
                .as("commentId in %s", listed).isEqualTo(place.commentId());
        assertThat(text(listed, "fileName")).isEqualTo(name);
        assertThat(listed.path("size").asLong(-1)).isEqualTo(content.length);
        assertThat(inlineIds(writer, place.issueKey())).as("inline attachments of %s", place.issueKey())
                .containsExactly(id);
        ApiResponse downloaded = download(writer, place.issueKey(), id);
        assertThat(downloaded.status()).as(downloaded.request()).isEqualTo(200);
        assertThat(downloaded.sha256()).as("sha256 of the attached image").isEqualTo(sha256(content));
        assertReadsLikeUnknown(writer, id, "the attached image on the temporary path");
        if (write.internal()) {
            assertThat(entry(st.agent(), place.issueKey(), id).path("internal").asBoolean(false))
                    .as("internal, for staff").isTrue();
            assertThat(entry(st.customer(), place.issueKey(), id)).as("the internal image for the customer").isNull();
        } else {
            assertThat(download(st.customer(), place.issueKey(), id).sha256()).as("sha256 for the customer")
                    .isEqualTo(sha256(content));
            assertThat(download(st.agent(), place.issueKey(), id).sha256()).as("sha256 for the agent")
                    .isEqualTo(sha256(content));
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Write.class, names = {"CONSOLE_INTERNAL_COMMENT", "CONSOLE_INTERNAL_COMMENT_EDIT"})
    @DisplayName("S5-AC-24 an image attached to an internal comment is not there for customers: not in the list, "
            + "and the unknown-ID 404 for download, range request, HEAD, and delete; staff still read it")
    void internalImageIsHiddenFromCustomers(Write write) {
        Stage st = ImageSteps.stage(fixtures);
        Company elsewhere = fixtures.createCompany(st.admin());
        Session outsider = fixtures.customer(st.admin(), elsewhere.code());
        String ownKeyOfOutsider = fixtures.fileIssue(outsider, elsewhere.code()).key();
        byte[] content = png();
        String name = "internal-" + Unique.number() + ".png";
        String id = uploaded(st.agent(), name, content);
        write.request(st, List.of(id)).expect(write.success());

        ApiResponse page = st.customer().client().get(st.customer().api("/issues/" + st.key() + "/attachments"))
                .query("size", 100).expect(200);

        assertThat(page.text()).as("the customer's attachment list").doesNotContain(id).doesNotContain(name);
        assertThat(page.json().path("totalElements").asLong(-1)).as("totalElements for the customer").isZero();
        assertAttachmentLikeUnknown(st.customer(), st.key(), st.key(), id, "the reporter");
        assertAttachmentLikeUnknown(st.colleague(), st.key(), st.key(), id, "a colleague");
        assertAttachmentLikeUnknown(outsider, ownKeyOfOutsider, st.key(), id, "a customer of another company");
        assertThat(download(st.agent(), st.key(), id).body()).as("the image for the agent afterwards")
                .isEqualTo(content);
    }

    @Test
    @DisplayName("S5-AC-24 an image attached to a public comment follows the issue: a colleague reads it, a customer "
            + "of another company gets the unknown-ID 404 and it stays")
    void publicImageFollowsTheIssue() {
        Stage st = ImageSteps.stage(fixtures);
        Company elsewhere = fixtures.createCompany(st.admin());
        Session outsider = fixtures.customer(st.admin(), elsewhere.code());
        String ownKeyOfOutsider = fixtures.fileIssue(outsider, elsewhere.code()).key();
        byte[] content = png();
        String id = ImageSteps.attachedByComment(st.customer(), st.key(), content);

        assertAttachmentLikeUnknown(outsider, ownKeyOfOutsider, st.key(), id, "a customer of another company");

        assertThat(inlineIds(outsider, ownKeyOfOutsider)).as("images on the outsider's own issue").isEmpty();
        assertThat(download(st.colleague(), st.key(), id).body()).as("the image for a colleague").isEqualTo(content);
        assertThat(download(st.customer(), st.key(), id).body()).as("the image for its uploader").isEqualTo(content);
    }

    @Test
    @DisplayName("S5-AC-25 only the IDs sent are attached: an ID the content names but the request does not send "
            + "stays temporary, an ID sent but not named is attached, and the content is stored as written")
    void onlySentIdsAreAttached() {
        Stage st = ImageSteps.stage(fixtures);
        String named = uploaded(st.customer());
        String sent = uploaded(st.customer());
        String content = "Look: ![screen](attachment:" + named + ") " + Unique.number();

        Comment comment = commentWith(st.customer(), st.key(), content, List.of(sent));

        assertThat(text(TalkFlow.comment(st.customer(), st.key(), comment.id()), "content")).isEqualTo(content);
        assertThat(inlineIds(st.customer(), st.key())).as("inline attachments of the issue").containsExactly(sent);
        assertThat(entry(st.customer(), st.key(), sent).path("commentId").asLong(-1)).isEqualTo(comment.id());
        assertThat(read(st.customer(), named).status()).as("the image named but not sent, on the temporary path")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("S5-AC-20 an ID given twice counts once, and 20 images go with one request")
    void twentyImagesAndAnIdTwice() {
        Stage st = ImageSteps.stage(fixtures);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < ImageSteps.MAX_IMAGES; i++) {
            ids.add(uploaded(st.customer()));
        }

        ApiResponse saved = TalkFlow.addRequest(st.customer(), st.key(), body("content", Unique.text("Twenty"),
                "imageIds", ids)).send();
        assertThat(saved.status()).as("%s: %s", saved.request(), saved.snippet()).isEqualTo(201);
        String twice = uploaded(st.customer());
        ApiResponse savedTwice = TalkFlow.addRequest(st.customer(), st.key(), body("content", Unique.text("Twice"),
                "imageIds", List.of(twice, twice))).send();

        assertThat(savedTwice.status()).as("%s: %s", savedTwice.request(), savedTwice.snippet()).isEqualTo(201);
        ids.add(twice);
        assertThat(inlineIds(st.customer(), st.key())).as("inline attachments of the issue")
                .containsExactlyInAnyOrderElementsOf(ids);
    }

    @Test
    @DisplayName("S5-AC-26, S5-AC-24 an image taken out of the content stays an attachment; its uploader and an "
            + "admin delete it like any attachment")
    void imageTakenOutOfTheContentStays() {
        Stage st = ImageSteps.stage(fixtures);
        String first = uploaded(st.customer());
        String second = uploaded(st.customer());
        Comment comment = commentWith(st.customer(), st.key(),
                "![a](attachment:" + first + ") ![b](attachment:" + second + ")", List.of(first, second));

        TalkFlow.editContent(st.customer(), st.key(), comment.id(), Unique.text("No images any more"));

        assertThat(inlineIds(st.customer(), st.key())).as("inline attachments after the edit")
                .containsExactlyInAnyOrder(first, second);
        assertThat(download(st.customer(), st.key(), first).status()).as("an image taken out of the content")
                .isEqualTo(200);
        st.customer().client().delete(path(st.customer(), st.key(), first)).expect(204);
        st.admin().client().delete(path(st.admin(), st.key(), second)).expect(204);
        assertThat(download(st.customer(), st.key(), first).status()).as("deleted by its uploader").isEqualTo(404);
        assertThat(download(st.customer(), st.key(), second).status()).as("deleted by an admin").isEqualTo(404);
    }

    @Test
    @DisplayName("S5-AC-27 deleting a comment deletes the images attached to it as it deletes the comment's other "
            + "attachments; an image on another comment stays")
    void deletingACommentDeletesItsImages() {
        Stage st = ImageSteps.stage(fixtures);
        AttachmentRows rows = new AttachmentRows(db);
        byte[] image = png();
        String id = uploaded(st.customer(), image);
        Comment comment = commentWith(st.customer(), st.key(), Unique.text("To delete"), List.of(id));
        String file = AttachmentSteps.uploadToComment(st.customer(), st.key(), comment.id(), "plain.bin",
                bytes(100, Unique.number())).expectStatus(201).jsonText("id");
        byte[] kept = png();
        String onAnotherComment = ImageSteps.attachedByComment(st.customer(), st.key(), kept);

        TalkFlow.delete(st.customer(), st.key(), comment.id());

        assertThat(download(st.customer(), st.key(), id).status()).as("the image of the deleted comment")
                .isEqualTo(404);
        assertThat(download(st.customer(), st.key(), file).status()).as("the file of the deleted comment")
                .isEqualTo(404);
        assertThat(trace(entry(st.agent(), st.key(), id))).as("what the list keeps of the image")
                .isEqualTo(trace(entry(st.agent(), st.key(), file)));
        Wait.untilAsserted("the image's content is removed", () -> assertThat(rows.contentCopies(image)).isZero());
        assertThat(download(st.customer(), st.key(), onAnotherComment).body()).as("an image on another comment")
                .isEqualTo(kept);
    }

    /** What an attachment list entry says of a deleted attachment: not listed, or its trace. */
    private static String trace(JsonNode entry) {
        return entry == null ? "not listed"
                : "deleted=" + entry.path("deleted") + " fileName=" + entry.path("fileName") + " deletedAt null="
                        + entry.path("deletedAt").isNull();
    }

    @Test
    @DisplayName("S5-AC-20 an issue edit that sends only its version and imageIds attaches the images and leaves "
            + "one history entry each, without raising the version (acceptance.md 5절 15)")
    void issueEditWithImagesOnlyKeepsTheVersion() {
        Stage st = ImageSteps.stage(fixtures);
        JsonNode before = ImageSteps.detail(st.customer(), st.key());
        String id = uploaded(st.customer());

        IssueFlow.editRequest(st.customer(), st.key(), body("version", before.path("version").asLong(),
                "imageIds", List.of(id))).expect(200);

        assertThat(inlineIds(st.customer(), st.key())).as("inline attachments of the issue").containsExactly(id);
        JsonNode after = ImageSteps.detail(st.customer(), st.key());
        assertThat(after.path("version").asLong(-1)).as("version").isEqualTo(before.path("version").asLong());
        assertThat(text(after, "content")).isEqualTo(text(before, "content"));
        assertThat(attachmentHistory(st.agent(), st.key())).extracting(e -> text(e, "newValue")).containsExactly(id);
    }

    @Test
    @DisplayName("S5-AC-20 a comment edit that sends the content it has and imageIds attaches the images to the "
            + "comment without marking it edited (acceptance.md 5절 15)")
    void commentEditWithImagesOnlyIsNoEdit() {
        Stage st = ImageSteps.stage(fixtures);
        long commentId = st.customerComment().id();
        String content = text(TalkFlow.comment(st.customer(), st.key(), commentId), "content");
        String id = uploaded(st.customer());

        TalkFlow.edit(st.customer(), st.key(), commentId, body("content", content, "imageIds", List.of(id)));

        assertThat(entry(st.customer(), st.key(), id)).as("the image in the list").isNotNull();
        assertThat(entry(st.customer(), st.key(), id).path("commentId").asLong(-1)).isEqualTo(commentId);
        JsonNode after = TalkFlow.comment(st.customer(), st.key(), commentId);
        assertThat(TalkFlow.isNull(after, "editedAt")).as("editedAt is null in %s", after).isTrue();
        assertThat(text(after, "content")).isEqualTo(content);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Write.class, names = {"PORTAL_FILING", "PORTAL_FILING_MULTIPART", "ON_BEHALF_FILING"})
    @DisplayName("S5-AC-20 a filing sent again with its Idempotency-Key answers the first issue, not a 400 for "
            + "images that are attached by now (acceptance.md 5절 16)")
    void filingSentAgainWithItsKey(Write write) {
        Stage st = ImageSteps.stage(fixtures);
        String id = uploaded(write.writer(st));
        String idempotencyKey = "image-" + UUID.randomUUID();

        ApiResponse first = write.request(st, List.of(id)).header("Idempotency-Key", idempotencyKey).send();
        ApiResponse again = write.request(st, List.of(id)).header("Idempotency-Key", idempotencyKey).send();

        assertThat(first.status()).as("%s: %s", first.request(), first.snippet()).isEqualTo(201);
        assertThat(again.status()).as("sent again: %s", again.snippet()).isEqualTo(201);
        assertThat(again.jsonText("key")).as("key of the answer to the second request").isEqualTo(first.jsonText("key"));
        assertThat(inlineIds(st.customer(), first.jsonText("key"))).containsExactly(id);
        assertThat(TalkFlow.items(IssueFlow.list(st.customer())).stream()
                .filter(issue -> st.marker().equals(text(issue, "title")))).as("issues titled %s", st.marker())
                .hasSize(1);
    }
}
