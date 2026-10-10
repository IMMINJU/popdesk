package io.popdesk.helpdesk.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Which {@code {id}} values of an attachment path are read as a UUID (verify.md V-8): only the 36
 * characters a UUID is written with. {@code UUID.fromString} also takes shorter groups and signs; those
 * would be second spellings of one attachment's ID, so they are not taken.
 */
class AttachmentIdTest {

    @Test
    @DisplayName("V-8 a UUID as it is written is taken, in lower and upper case")
    void writtenUuidIsTaken() {
        UUID id = UUID.randomUUID();

        assertThat(AttachmentService.attachmentId(id.toString())).contains(id);
        assertThat(AttachmentService.attachmentId(id.toString().toUpperCase())).contains(id);
    }

    @ParameterizedTest(name = "V-8 \"{0}\" is not an attachment ID")
    @ValueSource(strings = {
            "",
            "1-1-1-1-1",
            "0000000-00000-0000-0000-000000000000",
            "+0000000-0000-0000-0000-000000000000",
            "00000000-0000-0000-0000-00000000000",
            "00000000000000000000000000000000",
            "00000000-0000-0000-0000-000000000000 ",
            "{00000000-0000-0000-0000-000000000000}"})
    @DisplayName("V-8 other spellings that UUID.fromString would take, and values that are not UUIDs, are not taken")
    void otherSpellingsAreNotTaken(String id) {
        assertThat(AttachmentService.attachmentId(id)).isEmpty();
    }

    @Test
    @DisplayName("V-8 a missing ID is not taken")
    void missingIdIsNotTaken() {
        assertThat(AttachmentService.attachmentId(null)).isEmpty();
    }
}
