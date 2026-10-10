package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.path;

import io.popdesk.attachment.AttachmentSteps.Scene;
import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An attachment {@code {id}} that does not have the shape of a UUID (verify.md V-8, conventions
 * "Attachment paths"): no attachment has such an ID, so every endpoint that takes {@code {id}}
 * answers as it does for an ID that does not exist, 404 {@code NOT_FOUND} with the same body, not a
 * 400 about the format.
 *
 * <p>Each request is compared with the same request for a random UUID, on the same client and the
 * same issue, which the caller can see.
 */
class AttachmentIdShapeTest extends BareAppTest {

    /** IDs that are not UUIDs: a word, a number, one character too many or wrong, and a long value. */
    static final List<String> NOT_UUIDS = List.of(
            "abc",
            "12345",
            "not-a-uuid-at-all",
            "00000000-0000-0000-0000-00000000000g",
            "00000000-0000-0000-0000-0000000000000",
            "a".repeat(300));

    private AttachmentSteps steps;

    @BeforeEach
    void setUp() {
        steps = new AttachmentSteps(fixtures);
    }

    @Test
    @DisplayName("V-8 S0-AC-102 on the portal, downloading an attachment whose ID is not a UUID is the 404 of an unknown ID")
    void portalDownload() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());

        assertLikeUnknownId("download", s.customer(), issue.key(), (p, c) -> c.client().get(p), true);
    }

    @Test
    @DisplayName("V-8 S0-AC-102 on the portal, a range request for an attachment whose ID is not a UUID is the 404 of an unknown ID")
    void portalRange() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());

        assertLikeUnknownId("range", s.customer(), issue.key(),
                (p, c) -> c.client().get(p).header("Range", "bytes=0-9"), true);
    }

    @Test
    @DisplayName("V-8 S0-AC-102 on the portal, HEAD of an attachment whose ID is not a UUID is the 404 of an unknown ID")
    void portalHead() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());

        assertLikeUnknownId("HEAD", s.customer(), issue.key(), (p, c) -> c.client().head(p), false);
    }

    @Test
    @DisplayName("V-8 S0-AC-102 on the portal, deleting an attachment whose ID is not a UUID is the 404 of an unknown ID")
    void portalDelete() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());

        assertLikeUnknownId("delete", s.customer(), issue.key(), (p, c) -> c.client().delete(p), true);
    }

    @Test
    @DisplayName("V-8 S0-AC-102 on the console, downloading an attachment whose ID is not a UUID is the 404 of an unknown ID")
    void consoleDownload() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());

        assertLikeUnknownId("download", s.admin(), issue.key(), (p, c) -> c.client().get(p), true);
    }

    @Test
    @DisplayName("V-8 S0-AC-102 on the console, a range request for an attachment whose ID is not a UUID is the 404 of an unknown ID")
    void consoleRange() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());

        assertLikeUnknownId("range", s.admin(), issue.key(),
                (p, c) -> c.client().get(p).header("Range", "bytes=0-9"), true);
    }

    @Test
    @DisplayName("V-8 S0-AC-102 on the console, HEAD of an attachment whose ID is not a UUID is the 404 of an unknown ID")
    void consoleHead() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());

        assertLikeUnknownId("HEAD", s.admin(), issue.key(), (p, c) -> c.client().head(p), false);
    }

    @Test
    @DisplayName("V-8 S0-AC-102 on the console, deleting an attachment whose ID is not a UUID is the 404 of an unknown ID")
    void consoleDelete() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());

        assertLikeUnknownId("delete", s.admin(), issue.key(), (p, c) -> c.client().delete(p), true);
    }

    /**
     * For every ID of {@link #NOT_UUIDS}, {@code caller}'s request under {@code key} has the status and
     * the body of its request for a random UUID, which is 404 {@code NOT_FOUND}. A {@code HEAD}
     * response has no body, so there only the status is compared.
     */
    private static void assertLikeUnknownId(String what, Session caller, String key,
            BiFunction<String, Session, ApiRequest> request, boolean hasBody) {
        ApiResponse unknown = request.apply(path(caller, key, UUID.randomUUID().toString()), caller).send();
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(unknown.status()).as("%s of an unknown ID: %s", what, unknown).isEqualTo(404);
        if (hasBody) {
            softly.assertThat(unknown.errorCode()).as("code of %s of an unknown ID", what).isEqualTo("NOT_FOUND");
        }
        for (String id : NOT_UUIDS) {
            ApiResponse actual = request.apply(path(caller, key, id), caller).send();
            String shown = id.length() > 40 ? id.substring(0, 40) + "... (" + id.length() + " characters)" : id;
            softly.assertThat(actual.status()).as("status of %s with ID %s: %s", what, shown, actual.snippet())
                    .isEqualTo(404);
            if (hasBody) {
                softly.assertThat(actual.errorCode()).as("code of %s with ID %s", what, shown).isEqualTo("NOT_FOUND");
                softly.assertThat(actual.text()).as("body of %s with ID %s", what, shown).isEqualTo(unknown.text());
            }
        }
        softly.assertAll();
    }
}
