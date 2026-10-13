package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.bytes;
import static io.popdesk.attachment.AttachmentSteps.path;
import static io.popdesk.attachment.AttachmentSteps.text;
import static io.popdesk.attachment.ImageSteps.attachmentHistory;
import static io.popdesk.attachment.ImageSteps.commentWith;
import static io.popdesk.attachment.ImageSteps.history;
import static io.popdesk.attachment.ImageSteps.png;
import static io.popdesk.attachment.ImageSteps.uploaded;
import static io.popdesk.issue.TalkFlow.body;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.ImageSteps.Stage;
import io.popdesk.attachment.ImageSteps.Write;
import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * Attachments in the history of an issue (ATT-8, {@code GET /api/console/issues/{key}/history}): one
 * {@code CHANGED} entry with the field {@code ATTACHMENT} per attachment that is added to an issue
 * that is already there ({@code newValue} is its ID) or deleted ({@code oldValue} is its ID), with who
 * and when, and never a file name.
 */
class AttachmentHistoryTest extends BareAppTest {

    /** How an attachment gets onto an issue that is already there. */
    enum Way {
        UPLOAD_TO_THE_ISSUE,
        UPLOAD_TO_A_COMMENT,
        IMAGE_WITH_AN_ISSUE_EDIT,
        IMAGE_WITH_A_COMMENT,
        IMAGE_WITH_A_COMMENT_EDIT,
        IMAGE_WITH_AN_INTERNAL_COMMENT
    }

    private static boolean isNull(JsonNode entry, String field) {
        return entry.has(field) && entry.path(field).isNull();
    }

    /** An {@code ATTACHMENT} entry: the ID on one side, nothing on the other, no people, who, and when. */
    private static void assertEntry(JsonNode entry, String oldValue, String newValue, Session who, Instant at) {
        assertThat(text(entry, "kind")).as("kind of %s", entry).isEqualTo("CHANGED");
        assertThat(text(entry, "field")).as("field of %s", entry).isEqualTo("ATTACHMENT");
        assertThat(text(entry, "oldValue")).as("oldValue of %s", entry).isEqualTo(oldValue);
        assertThat(text(entry, "newValue")).as("newValue of %s", entry).isEqualTo(newValue);
        assertThat(isNull(entry, "oldValue") || isNull(entry, "newValue")).as("one value is null in %s", entry)
                .isTrue();
        assertThat(isNull(entry, "oldPerson") && isNull(entry, "newPerson")).as("no people in %s", entry).isTrue();
        assertThat(entry.path("actor").path("accountId").asLong(-1)).as("actor of %s", entry)
                .isEqualTo(who.accountId());
        assertThat(IssueFlow.time(entry, "createdAt")).as("createdAt of %s", entry).isEqualTo(at);
    }

    private static String historyText(Session staff, String key) {
        return staff.client().get("/api/console/issues/" + key + "/history").query("size", 100).expect(200).text();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Way.class)
    @DisplayName("S5-AC-40, S5-AC-43 a file uploaded to an issue or a comment, and an image attached with an issue "
            + "edit, a comment, or a comment edit, leaves one ATTACHMENT entry with its ID as newValue, who, and "
            + "when, and no file name")
    void attachingLeavesOneEntry(Way way) {
        Stage st = ImageSteps.stage(fixtures);
        String name = "history-" + Unique.number() + ".png";
        byte[] content = png();
        Session who = way == Way.IMAGE_WITH_AN_INTERNAL_COMMENT ? st.agent() : st.customer();
        String image = switch (way) {
            case UPLOAD_TO_THE_ISSUE, UPLOAD_TO_A_COMMENT -> null;
            default -> uploaded(who, name, content);
        };
        Instant at = clock.advance(Duration.ofSeconds(1));

        String id = switch (way) {
            case UPLOAD_TO_THE_ISSUE -> AttachmentSteps.upload(who, st.key(), name, content).expectStatus(201)
                    .jsonText("id");
            case UPLOAD_TO_A_COMMENT -> AttachmentSteps.uploadToComment(who, st.key(), st.customerComment().id(),
                    name, content).expectStatus(201).jsonText("id");
            case IMAGE_WITH_AN_ISSUE_EDIT -> attach(Write.PORTAL_ISSUE_EDIT, st, image);
            case IMAGE_WITH_A_COMMENT -> attach(Write.PORTAL_COMMENT, st, image);
            case IMAGE_WITH_A_COMMENT_EDIT -> attach(Write.PORTAL_COMMENT_EDIT, st, image);
            case IMAGE_WITH_AN_INTERNAL_COMMENT -> attach(Write.CONSOLE_INTERNAL_COMMENT, st, image);
        };

        assertThat(attachmentHistory(st.agent(), st.key())).as("ATTACHMENT entries of the issue").singleElement()
                .satisfies(entry -> assertEntry(entry, null, id, who, at));
        assertThat(historyText(st.agent(), st.key())).as("the history as sent").doesNotContain(name);
    }

    private static String attach(Write write, Stage st, String image) {
        write.request(st, List.of(image)).expect(write.success());
        return image;
    }

    @Test
    @DisplayName("S5-AC-40 two images attached with one comment leave an entry each")
    void eachImageHasItsOwnEntry() {
        Stage st = ImageSteps.stage(fixtures);
        String first = uploaded(st.customer());
        String second = uploaded(st.customer());
        Instant at = clock.advance(Duration.ofSeconds(1));

        commentWith(st.customer(), st.key(), Unique.text("Two images"), List.of(first, second));

        List<JsonNode> entries = attachmentHistory(st.agent(), st.key());
        assertThat(entries).extracting(e -> text(e, "newValue")).containsExactlyInAnyOrder(first, second);
        assertThat(entries).allSatisfy(entry -> assertEntry(entry, null, text(entry, "newValue"), st.customer(), at));
    }

    @Test
    @DisplayName("S5-AC-41, S5-AC-43 deleting an attachment leaves one ATTACHMENT entry with its ID as oldValue, "
            + "by the one who deleted it, and no file name")
    void deletingAnAttachmentLeavesOneEntry() {
        Stage st = ImageSteps.stage(fixtures);
        String name = "deleted-" + Unique.number() + ".bin";
        String id = AttachmentSteps.upload(st.customer(), st.key(), name, bytes(100, Unique.number()))
                .expectStatus(201).jsonText("id");
        Instant at = clock.advance(Duration.ofSeconds(1));

        st.admin().client().delete(path(st.admin(), st.key(), id)).expect(204);

        List<JsonNode> entries = attachmentHistory(st.agent(), st.key());
        assertThat(entries).as("ATTACHMENT entries: added, then deleted").hasSize(2);
        assertThat(text(entries.getFirst(), "newValue")).as("the entry of the upload").isEqualTo(id);
        assertEntry(entries.getLast(), id, null, st.admin(), at);
        assertThat(historyText(st.agent(), st.key())).as("the history as sent").doesNotContain(name);
    }

    @Test
    @DisplayName("S5-AC-41 deleting a comment leaves an entry for each of its attachments, the file and the image")
    void deletingACommentLeavesAnEntryPerAttachment() {
        Stage st = ImageSteps.stage(fixtures);
        String image = uploaded(st.customer());
        Comment comment = commentWith(st.customer(), st.key(), Unique.text("To delete"), List.of(image));
        String file = AttachmentSteps.uploadToComment(st.customer(), st.key(), comment.id(), "plain.bin",
                bytes(100, Unique.number())).expectStatus(201).jsonText("id");
        int before = attachmentHistory(st.agent(), st.key()).size();
        Instant at = clock.advance(Duration.ofSeconds(1));

        TalkFlow.delete(st.customer(), st.key(), comment.id());

        List<JsonNode> entries = attachmentHistory(st.agent(), st.key());
        assertThat(before).as("entries for the image and the file being added").isEqualTo(2);
        List<JsonNode> deleted = entries.subList(Math.min(before, entries.size()), entries.size());
        assertThat(deleted).extracting(e -> text(e, "oldValue")).containsExactlyInAnyOrder(image, file);
        assertThat(deleted).allSatisfy(entry -> assertEntry(entry, text(entry, "oldValue"), null, st.customer(),
                at));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Write.class, names = {"PORTAL_FILING", "PORTAL_FILING_MULTIPART", "ON_BEHALF_FILING"})
    @DisplayName("S5-AC-42 an issue filed with files and images has one CREATED entry and none for its attachments")
    void filingLeavesOnlyCreated(Write write) {
        Stage st = ImageSteps.stage(fixtures);
        String image = uploaded(write.writer(st));

        ApiResponse filed = write.request(st, List.of(image)).expect(201);

        String key = filed.jsonText("key");
        assertThat(ImageSteps.inlineIds(st.agent(), key)).as("images of the new issue").containsExactly(image);
        assertThat(history(st.agent(), key)).as("history of the new issue").extracting(e -> text(e, "kind"))
                .containsExactly("CREATED");
    }

    @Test
    @DisplayName("S5-AC-45 requests that are refused leave no entry: an empty file, a comment with an image nobody "
            + "has, deleting an attachment that is not there")
    void refusedRequestsLeaveNoEntry() {
        Stage st = ImageSteps.stage(fixtures);
        String stored = AttachmentSteps.upload(st.customer(), st.key(), "stored.bin", bytes(100, Unique.number()))
                .expectStatus(201).jsonText("id");

        ApiResponse emptyFile = AttachmentSteps.upload(st.customer(), st.key(), "empty.bin", new byte[0]);
        ApiResponse unknownImage = TalkFlow.addRequest(st.customer(), st.key(), body("content",
                Unique.text("Refused"), "imageIds", List.of(uploaded(st.customer()), ImageSteps.unknownId()))).send();
        ApiResponse unknownAttachment = st.customer().client().delete(path(st.customer(), st.key(),
                ImageSteps.unknownId())).send();

        assertThat(List.of(emptyFile.status(), unknownImage.status(), unknownAttachment.status()))
                .as("statuses of the three refused requests").containsExactly(400, 400, 404);
        assertThat(attachmentHistory(st.agent(), st.key())).as("ATTACHMENT entries of the issue")
                .extracting(e -> text(e, "newValue")).containsExactly(stored);
    }
}
