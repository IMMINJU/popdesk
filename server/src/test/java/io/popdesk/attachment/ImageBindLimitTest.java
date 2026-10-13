package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.MAX_FILE;
import static io.popdesk.attachment.AttachmentSteps.bytes;
import static io.popdesk.attachment.AttachmentSteps.list;
import static io.popdesk.attachment.AttachmentSteps.text;
import static io.popdesk.attachment.ImageSteps.image;
import static io.popdesk.attachment.ImageSteps.inlineIds;
import static io.popdesk.attachment.ImageSteps.read;
import static io.popdesk.attachment.ImageSteps.uploaded;
import static io.popdesk.issue.TalkFlow.body;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.ImageSteps.Format;
import io.popdesk.attachment.ImageSteps.Stage;
import io.popdesk.attachment.ImageSteps.Write;
import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Unique;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Attaching images under the limits a write already has: the 50 MB of public attachments of one issue
 * (ATT-2) and a closed issue (ATT-1).
 */
class ImageBindLimitTest extends BareAppTest {

    @Test
    @DisplayName("S5-AC-23 an image that takes the issue's public attachments to exactly 50 MB is attached; the next "
            + "one is 400 ISSUE_ATTACHMENTS_TOO_LARGE, its comment is not stored and it stays temporary; an image on an "
            + "internal comment is not counted")
    void fiftyMegabytesOfPublicAttachments() {
        Stage st = ImageSteps.stage(fixtures);
        byte[] tenMb = bytes(MAX_FILE, 31);
        for (int i = 0; i < 4; i++) {
            AttachmentSteps.upload(st.customer(), st.key(), "file-" + i + ".bin", tenMb).expectStatus(201);
        }
        String toTheLimit = uploaded(st.customer(), image(Format.PNG, MAX_FILE, Unique.number()));
        String over = uploaded(st.customer());
        String ofAgent = uploaded(st.agent());
        String internal = uploaded(st.agent());
        String refusedContent = Unique.text("Over 50 MB");

        ApiResponse atTheLimit = TalkFlow.addRequest(st.customer(), st.key(), body("content",
                Unique.text("Exactly 50 MB"), "imageIds", List.of(toTheLimit))).send();
        ApiResponse refused = TalkFlow.addRequest(st.customer(), st.key(), body("content", refusedContent,
                "imageIds", List.of(over))).send();
        ApiResponse refusedForAgent = TalkFlow.addRequest(st.agent(), st.key(), body("content", refusedContent,
                "imageIds", List.of(ofAgent))).send();
        ApiResponse onInternal = TalkFlow.addRequest(st.agent(), st.key(), body("content", Unique.text("Internal"),
                "internal", true, "imageIds", List.of(internal))).send();

        assertThat(atTheLimit.status()).as("to exactly 50 MB: %s", atTheLimit.snippet()).isEqualTo(201);
        for (ApiResponse tooLarge : List.of(refused, refusedForAgent)) {
            assertThat(tooLarge.status()).as("%s: %s", tooLarge.request(), tooLarge.snippet()).isEqualTo(400);
            assertThat(tooLarge.errorCode()).isEqualTo("ISSUE_ATTACHMENTS_TOO_LARGE");
        }
        assertThat(onInternal.status()).as("on an internal comment: %s", onInternal.snippet()).isEqualTo(201);
        assertThat(TalkFlow.comments(st.agent(), st.key())).as("comments of the issue")
                .noneMatch(comment -> refusedContent.equals(text(comment, "content")));
        assertThat(read(st.customer(), over).status()).as("the refused image, still temporary").isEqualTo(200);
        assertThat(inlineIds(st.agent(), st.key())).as("inline attachments of the issue")
                .containsExactlyInAnyOrder(toTheLimit, internal);
        assertThat(list(st.customer(), st.key()).stream().mapToLong(n -> n.path("size").asLong()).sum())
                .as("public bytes of the issue").isEqualTo(AttachmentSteps.MAX_PUBLIC_PER_ISSUE);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Write.class, names = {"PORTAL_COMMENT", "CONSOLE_COMMENT", "PORTAL_ISSUE_EDIT",
            "CONSOLE_ISSUE_EDIT"})
    @DisplayName("S5-AC-28 a comment or an edit on a closed issue is 400 ISSUE_CLOSED with images as without; the "
            + "images stay temporary and are attached to another issue afterwards")
    void closedIssueTakesNoImages(Write write) {
        Stage st = ImageSteps.stage(fixtures);
        String id = uploaded(write.writer(st));
        IssueFlow.statusRequest(st.agent(), st.key(), "CLOSED").expect(200);

        ApiResponse refused = write.request(st, List.of(id)).send();

        assertThat(refused.status()).as("%s: %s", refused.request(), refused.snippet()).isEqualTo(400);
        assertThat(refused.errorCode()).isEqualTo("ISSUE_CLOSED");
        assertThat(write.saved(st)).as("the text of the refused %s is stored", write).isFalse();
        assertThat(inlineIds(st.agent(), st.key())).as("inline attachments of the closed issue").isEmpty();
        assertThat(read(write.writer(st), id).status()).as("the image, still temporary").isEqualTo(200);
        Issue open = fixtures.fileIssue(st.customer(), st.code());
        ImageSteps.commentWith(write.writer(st), open.key(), Unique.text("On an open issue"), List.of(id));
        assertThat(inlineIds(st.agent(), open.key())).as("inline attachments of the open issue").containsExactly(id);
    }
}
