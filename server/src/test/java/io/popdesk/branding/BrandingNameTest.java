package io.popdesk.branding;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Session;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/**
 * The display name (S9-AC-09): the rules of a member's name, NFC and trimmed, plus direction marks, since the name
 * goes into mail headers (TM-66). A refused name is 400 {@code INVALID_INPUT} with {@code displayName} and its code,
 * and the branding stays as it was.
 */
class BrandingNameTest extends BrandingAppTest {

    static Stream<Arguments> wrongNames() {
        return Stream.of(
                Arguments.of("empty", "", "REQUIRED"),
                Arguments.of("null", null, "REQUIRED"),
                Arguments.of("spaces only", "   ", "BLANK"),
                Arguments.of("an ideographic space only", "　", "BLANK"),
                Arguments.of("51 characters", "D".repeat(51), "TOO_LONG"),
                Arguments.of("a line feed", "Desk\nName", "INVALID_FORMAT"),
                Arguments.of("a carriage return and line feed", "Desk\r\nBcc: x@example.com", "INVALID_FORMAT"),
                Arguments.of("a tab inside", "Desk\tName", "INVALID_FORMAT"),
                Arguments.of("a control character", "Desk\u0007Name", "INVALID_FORMAT"),
                Arguments.of("U+2028 line separator", "Desk Name", "INVALID_FORMAT"),
                Arguments.of("U+2029 paragraph separator", "Desk Name", "INVALID_FORMAT"),
                Arguments.of("U+202E right-to-left override", "Desk‮emaN", "INVALID_FORMAT"),
                Arguments.of("U+202A left-to-right embedding", "‪Desk", "INVALID_FORMAT"),
                Arguments.of("U+2066 left-to-right isolate", "Desk⁦Name", "INVALID_FORMAT"),
                Arguments.of("U+2069 pop directional isolate", "Desk⁩Name", "INVALID_FORMAT"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wrongNames")
    @DisplayName("S9-AC-09 a wrong display name is 400 INVALID_INPUT with displayName and its code, and changes "
            + "nothing")
    void wrongNameChangesNothing(String what, String name, String code) {
        Session admin = fixtures.signInAdmin();
        JsonNode before = save(admin, uniqueName(), EMERALD);

        ApiResponse refused = update(admin, body(version(before), name, EMERALD)).send();

        assertInvalid(refused, "displayName", code);
        assertThat(canonical(branding(admin))).as("the branding after the refusal").isEqualTo(canonical(before));
    }

    @Test
    @DisplayName("S9-AC-09 the display name is saved in NFC with leading and trailing whitespace removed, and its "
            + "50 characters are counted after that")
    void nameIsNormalizedThenCounted() {
        Session admin = fixtures.signInAdmin();
        save(admin, uniqueName(), null);
        String base = uniqueName();
        // 49 characters, then e and a combining acute accent: 51 code points as sent, 50 as one é in NFC.
        String padded = base + "x".repeat(49 - base.length());
        String sent = " \t" + padded + "é  ";
        String expected = padded + "é";

        JsonNode saved = update(admin, body(version(branding(admin)), sent, null)).expect(200).json();

        assertThat(saved.path("displayName").asString(null)).as("displayName of the answer").isEqualTo(expected);
        assertThat(branding(admin).path("displayName").asString(null)).as("displayName read again")
                .isEqualTo(expected);
        assertThat(publicBranding(portal()).path("displayName").asString(null)).as("public displayName")
                .isEqualTo(expected);
    }
}
