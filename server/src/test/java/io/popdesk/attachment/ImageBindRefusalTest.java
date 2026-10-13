package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.download;
import static io.popdesk.attachment.AttachmentSteps.fieldCode;
import static io.popdesk.attachment.AttachmentSteps.ids;
import static io.popdesk.attachment.AttachmentSteps.list;
import static io.popdesk.attachment.AttachmentSteps.text;
import static io.popdesk.attachment.ImageSteps.attachedByComment;
import static io.popdesk.attachment.ImageSteps.commentWith;
import static io.popdesk.attachment.ImageSteps.history;
import static io.popdesk.attachment.ImageSteps.inlineIds;
import static io.popdesk.attachment.ImageSteps.png;
import static io.popdesk.attachment.ImageSteps.read;
import static io.popdesk.attachment.ImageSteps.unknownId;
import static io.popdesk.attachment.ImageSteps.uploaded;
import static io.popdesk.issue.TalkFlow.body;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.ImageSteps.Stage;
import io.popdesk.attachment.ImageSteps.Write;
import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * {@code imageIds} that cannot be attached (S5-AC-21, TM-33): whatever is wrong with an ID, the answer
 * is the 400 {@code TEMPORARY_IMAGE_INVALID} an ID nobody has gets, byte for byte, and the request
 * saves nothing: no issue, no comment, no changed content, and no image attached, the good ones of
 * the same request neither.
 */
class ImageBindRefusalTest extends BareAppTest {

    private static final String INVALID = "TEMPORARY_IMAGE_INVALID";

    /** What the customer sends as an image ID with a new comment. */
    enum Unattachable {
        TEMPORARY_IMAGE_OF_A_COLLEAGUE,
        TEMPORARY_IMAGE_OF_AN_AGENT,
        IMAGE_ATTACHED_TO_THIS_ISSUE,
        IMAGE_ATTACHED_TO_ANOTHER_ISSUE,
        OWN_ORDINARY_ATTACHMENT,
        ATTACHMENT_OF_AN_INTERNAL_COMMENT,
        IMAGE_OF_AN_INTERNAL_COMMENT,
        ATTACHMENT_OF_ANOTHER_COMPANY,
        IMAGE_ATTACHED_IN_ANOTHER_COMPANY,
        NOT_A_UUID,
        ONE_WRONG_AMONG_THREE
    }

    /** The IDs a case sends, and the attachment that must still be where it was afterwards (or null). */
    private record Sent(List<Object> ids, Session owner, String ownerKey, String ownedId) {

        static Sent of(Object... ids) {
            return new Sent(List.of(ids), null, null, null);
        }

        static Sent ofAttachment(String id, Session owner, String ownerKey) {
            return new Sent(List.of(id), owner, ownerKey, id);
        }
    }

    private Sent prepare(Unattachable what, Stage st) {
        Session customer = st.customer();
        return switch (what) {
            case TEMPORARY_IMAGE_OF_A_COLLEAGUE -> Sent.of(uploaded(st.colleague()));
            case TEMPORARY_IMAGE_OF_AN_AGENT -> Sent.of(uploaded(st.agent()));
            case IMAGE_ATTACHED_TO_THIS_ISSUE -> Sent.ofAttachment(attachedByComment(customer, st.key(), png()),
                    customer, st.key());
            case IMAGE_ATTACHED_TO_ANOTHER_ISSUE -> {
                Issue other = fixtures.fileIssue(customer, st.code());
                yield Sent.ofAttachment(attachedByComment(customer, other.key(), png()), customer, other.key());
            }
            case OWN_ORDINARY_ATTACHMENT -> Sent.ofAttachment(AttachmentSteps.upload(customer, st.key(), "plain.png",
                    png()).expectStatus(201).jsonText("id"), customer, st.key());
            case ATTACHMENT_OF_AN_INTERNAL_COMMENT -> Sent.ofAttachment(AttachmentSteps.uploadToComment(st.agent(),
                    st.key(), st.internalComment().id(), "internal.png", png()).expectStatus(201).jsonText("id"),
                    st.agent(), st.key());
            case IMAGE_OF_AN_INTERNAL_COMMENT -> {
                String id = uploaded(st.agent());
                TalkFlow.add(st.agent(), st.key(), body("content", Unique.text("Internal"), "internal", true,
                        "imageIds", List.of(id)));
                yield Sent.ofAttachment(id, st.agent(), st.key());
            }
            case ATTACHMENT_OF_ANOTHER_COMPANY -> {
                Company elsewhere = fixtures.createCompany(st.admin());
                Session outsider = fixtures.customer(st.admin(), elsewhere.code());
                Issue theirs = fixtures.fileIssue(outsider, elsewhere.code());
                yield Sent.ofAttachment(AttachmentSteps.upload(outsider, theirs.key(), "theirs.png", png())
                        .expectStatus(201).jsonText("id"), outsider, theirs.key());
            }
            case IMAGE_ATTACHED_IN_ANOTHER_COMPANY -> {
                Company elsewhere = fixtures.createCompany(st.admin());
                Session outsider = fixtures.customer(st.admin(), elsewhere.code());
                Issue theirs = fixtures.fileIssue(outsider, elsewhere.code());
                yield Sent.ofAttachment(attachedByComment(outsider, theirs.key(), png()), outsider, theirs.key());
            }
            case NOT_A_UUID -> Sent.of("not-a-uuid");
            case ONE_WRONG_AMONG_THREE -> Sent.of(uploaded(customer), unknownId(), uploaded(customer));
        };
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Unattachable.class)
    @DisplayName("S5-AC-21 a comment that names an image the caller may not attach gets the same 400 "
            + "TEMPORARY_IMAGE_INVALID, body and all, as one that names an unknown ID; no comment is stored, nothing "
            + "is attached, and what the ID belongs to stays where it was")
    void everyCaseAnswersLikeAnUnknownId(Unattachable what) {
        Stage st = ImageSteps.stage(fixtures);
        Session customer = st.customer();
        // Attaching works for this customer on this issue, so each refusal below is about the ID.
        String good = attachedByComment(customer, st.key(), png());
        Sent sent = prepare(what, st);
        List<String> attachedBefore = ids(list(customer, st.key()));
        String content = Unique.text("Refused comment");

        ApiResponse unknown = TalkFlow.addRequest(customer, st.key(), body("content", content, "imageIds",
                List.of(unknownId()))).send();
        ApiResponse refused = TalkFlow.addRequest(customer, st.key(), body("content", content, "imageIds",
                sent.ids())).send();

        assertThat(unknown.status()).as("an unknown ID: %s", unknown.snippet()).isEqualTo(400);
        assertThat(unknown.errorCode()).isEqualTo(INVALID);
        assertThat(refused.status()).as("%s: %s", what, refused.snippet()).isEqualTo(unknown.status());
        assertThat(refused.text()).as("body for %s, like for an unknown ID", what).isEqualTo(unknown.text());
        assertThat(TalkFlow.comments(customer, st.key())).as("comments of the issue")
                .noneMatch(comment -> content.equals(text(comment, "content")));
        assertThat(ids(list(customer, st.key()))).as("attachments of the issue the customer sees")
                .containsExactlyInAnyOrderElementsOf(attachedBefore).contains(good);
        if (sent.ownedId() != null) {
            assertThat(download(sent.owner(), sent.ownerKey(), sent.ownedId()).status())
                    .as("the attachment under its own issue, for its owner").isEqualTo(200);
        }
        if (what == Unattachable.ONE_WRONG_AMONG_THREE) {
            List<Object> goodOnes = List.of(sent.ids().get(0), sent.ids().get(2));
            assertThat(goodOnes).as("the good images of the refused request, still temporary")
                    .allSatisfy(id -> assertThat(read(customer, (String) id).status()).isEqualTo(200));
            commentWith(customer, st.key(), Unique.text("Only the good ones"), goodOnes);
            assertThat(inlineIds(customer, st.key())).contains((String) goodOnes.get(0), (String) goodOnes.get(1));
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Write.class)
    @DisplayName("S5-AC-20, S5-AC-21, S5-AC-45 each request that takes imageIds refuses a good image sent with "
            + "another account's like an unknown ID, saves nothing, attaches neither, and leaves no history entry")
    void everyWriteRefusesWithoutSaving(Write write) {
        Stage st = ImageSteps.stage(fixtures);
        Session writer = write.writer(st);
        // The same request with an image of the writer alone works, on a stage of its own.
        Stage works = ImageSteps.stage(fixtures);
        write.request(works, List.of(uploaded(write.writer(works)))).expect(write.success());
        String good = uploaded(writer);
        String foreign = uploaded(st.colleague());
        List<JsonNode> historyBefore = history(st.agent(), st.key());
        List<String> attachedBefore = ids(list(st.agent(), st.key()));
        long issuesBefore = TalkFlow.items(IssueFlow.list(st.customer())).size();

        ApiResponse unknown = write.request(st, List.of(unknownId())).send();
        ApiResponse refused = write.request(st, List.of(good, foreign)).send();

        assertThat(unknown.status()).as("an unknown ID: %s", unknown.snippet()).isEqualTo(400);
        assertThat(unknown.errorCode()).isEqualTo(INVALID);
        assertThat(refused.status()).as("%s: %s", write, refused.snippet()).isEqualTo(unknown.status());
        assertThat(refused.text()).as("body, like for an unknown ID").isEqualTo(unknown.text());
        assertThat(write.saved(st)).as("the text of the refused %s is stored", write).isFalse();
        assertThat(TalkFlow.items(IssueFlow.list(st.customer())).size())
                .as("issues of the company").isEqualTo(issuesBefore);
        assertThat(read(writer, good).status()).as("the writer's image, still temporary").isEqualTo(200);
        assertThat(read(st.colleague(), foreign).status()).as("the other account's image, still temporary")
                .isEqualTo(200);
        assertThat(ids(list(st.agent(), st.key()))).as("attachments of the issue").isEqualTo(attachedBefore);
        assertThat(history(st.agent(), st.key())).as("history of the issue").isEqualTo(historyBefore);
    }

    @Test
    @DisplayName("S5-AC-21, S5-AC-30 a second before 24 hours an image is attached; a second after, it is refused "
            + "like an unknown ID and the comment is not stored")
    void expiredImageIsRefused() {
        Stage st = ImageSteps.stage(fixtures);
        String early = uploaded(st.customer());
        String late = uploaded(st.customer());

        clock.advance(ImageSteps.LIFETIME.minus(Duration.ofSeconds(1)));
        Session customer = fixtures.signInAgain(st.customer());
        ApiResponse justBefore = TalkFlow.addRequest(customer, st.key(), body("content", Unique.text("In time"),
                "imageIds", List.of(early))).send();
        clock.advance(Duration.ofSeconds(2));
        String content = Unique.text("Too late");
        ApiResponse unknown = TalkFlow.addRequest(customer, st.key(), body("content", content, "imageIds",
                List.of(unknownId()))).send();
        ApiResponse refused = TalkFlow.addRequest(customer, st.key(), body("content", content, "imageIds",
                List.of(late))).send();

        assertThat(justBefore.status()).as("a second before 24 hours: %s", justBefore.snippet()).isEqualTo(201);
        assertThat(unknown.status()).as("an unknown ID: %s", unknown.snippet()).isEqualTo(400);
        assertThat(unknown.errorCode()).isEqualTo(INVALID);
        assertThat(refused.status()).as("a second after 24 hours: %s", refused.snippet()).isEqualTo(400);
        assertThat(refused.text()).as("body, like for an unknown ID").isEqualTo(unknown.text());
        assertThat(TalkFlow.comments(customer, st.key())).as("comments of the issue")
                .noneMatch(comment -> content.equals(text(comment, "content")));
        assertThat(inlineIds(customer, st.key())).as("inline attachments of the issue").containsExactly(early);
    }

    @Test
    @DisplayName("S5-AC-21 more than 20 imageIds is 400 INVALID_INPUT with imageIds TOO_MANY, whatever the IDs are; "
            + "20 unknown ones are TEMPORARY_IMAGE_INVALID (acceptance.md 5절 14)")
    void twentyOneIdsAreInvalidInput() {
        Stage st = ImageSteps.stage(fixtures);
        // An image is attached on this issue first, so the refusals below are about the IDs sent.
        attachedByComment(st.customer(), st.key(), png());
        List<String> twenty = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            twenty.add(unknownId());
        }
        List<String> twentyOne = new ArrayList<>(twenty);
        twentyOne.add(unknownId());
        String content = Unique.text("Too many images");

        ApiResponse atTheLimit = TalkFlow.addRequest(st.customer(), st.key(), body("content", content, "imageIds",
                twenty)).send();
        ApiResponse over = TalkFlow.addRequest(st.customer(), st.key(), body("content", content, "imageIds",
                twentyOne)).send();

        assertThat(atTheLimit.status()).as("20 unknown IDs: %s", atTheLimit.snippet()).isEqualTo(400);
        assertThat(atTheLimit.errorCode()).isEqualTo(INVALID);
        assertThat(over.status()).as("21 IDs: %s", over.snippet()).isEqualTo(400);
        assertThat(over.errorCode()).isEqualTo("INVALID_INPUT");
        assertThat(fieldCode(over, "imageIds")).as("code of the field imageIds").isEqualTo("TOO_MANY");
        assertThat(TalkFlow.comments(st.customer(), st.key())).as("comments of the issue")
                .noneMatch(comment -> content.equals(text(comment, "content")));
    }
}
