package io.popdesk.helpdesk.mailin;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.TextInput;
import java.text.Normalizer;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The content of an issue or comment made from an email fitted in its limit (S12-AC-24, S12-AC-26, MAILIN-4).
 * Written from the Javadoc of {@link BodyFit} and the acceptance text alone. Lengths are code points of NFC text.
 */
class BodyFitTest {

    private static final String CLOSING = "(cut)";

    private static int length(String s) {
        return TextInput.length(s);
    }

    @Test
    @DisplayName("S12-AC-24: a body within the limit is kept as it is")
    void s12Ac24_bodyWithinLimit() {
        assertThat(BodyFit.fit("hello", List.of(), 10, CLOSING)).isEqualTo("hello");
        assertThat(BodyFit.fit("hello  \n", List.of(), 10, CLOSING)).isEqualTo("hello  \n");
        assertThat(BodyFit.fit("", List.of(), 10, CLOSING)).isEmpty();
    }

    @Test
    @DisplayName("S12-AC-24: a body of exactly the limit is kept; one more code point cuts it")
    void s12Ac24_limitBoundary() {
        assertThat(BodyFit.fit("abcdefghij", List.of(), 10, CLOSING)).isEqualTo("abcdefghij");
        // 9 code points: 2 of body, an empty line (2), and the closing line (5).
        assertThat(BodyFit.fit("abcdefghij", List.of(), 9, CLOSING)).isEqualTo("ab\n\n(cut)");
    }

    @Test
    @DisplayName("S12-AC-24: a cut body is as long as fits, then an empty line and the closing line")
    void s12Ac24_cutBodyFillsTheLimit() {
        String body = "x".repeat(50);
        for (int limit = 8; limit < 50; limit++) {
            String content = BodyFit.fit(body, List.of(), limit, CLOSING);
            assertThat(content).as("limit %d", limit).isEqualTo("x".repeat(limit - 7) + "\n\n" + CLOSING);
        }
    }

    @Test
    @DisplayName("S12-AC-24: spaces and line breaks at the cut are dropped")
    void s12Ac24_spacesAtTheCutAreDropped() {
        assertThat(BodyFit.fit("ab   cdefghijk", List.of(), 11, CLOSING)).isEqualTo("ab\n\n(cut)");
        assertThat(BodyFit.fit("ab\n\n\ncdefghijk", List.of(), 11, CLOSING)).isEqualTo("ab\n\n(cut)");
        assertThat(BodyFit.fit("  ab cdefghijk", List.of(), 11, CLOSING)).isEqualTo("  ab\n\n(cut)");
    }

    @Test
    @DisplayName("S12-AC-24: when no code point of the body fits, only the closing line is left")
    void s12Ac24_noRoomForBody() {
        assertThat(BodyFit.fit("abcdefghij", List.of(), 7, CLOSING)).isEqualTo("(cut)");
        assertThat(BodyFit.fit("abcdefghij", List.of(), 5, CLOSING)).isEqualTo("(cut)");
        assertThat(BodyFit.fit("abcdefghij", List.of(), 8, CLOSING)).isEqualTo("a\n\n(cut)");
        assertThat(BodyFit.fit("     abcdefghij", List.of(), 10, CLOSING)).isEqualTo("(cut)");
    }

    @Test
    @DisplayName("S12-AC-24: the body is cut at a code point, and lengths count code points")
    void s12Ac24_codePoints() {
        String grin = "\ud83d\ude00";
        assertThat(BodyFit.fit(grin.repeat(5), List.of(), 5, CLOSING)).isEqualTo(grin.repeat(5));
        assertThat(BodyFit.fit(grin.repeat(10), List.of(), 9, CLOSING)).isEqualTo(grin.repeat(2) + "\n\n(cut)");
        assertThat(BodyFit.fit("ab", List.of(), 9, grin.repeat(3))).isEqualTo("ab");
        assertThat(BodyFit.fit("abcdefghij", List.of(), 7, grin.repeat(3))).isEqualTo("ab\n\n" + grin.repeat(3));
    }

    @Test
    @DisplayName("S12-AC-24: the content is NFC and counted as NFC")
    void s12Ac24_nfc() {
        String decomposed = "e\u0301";
        assertThat(BodyFit.fit(decomposed + "x", List.of(), 10, CLOSING)).isEqualTo("\u00e9x");
        assertThat(BodyFit.fit(decomposed.repeat(5), List.of(), 5, CLOSING)).isEqualTo("\u00e9".repeat(5));
        assertThat(BodyFit.fit(decomposed.repeat(10), List.of(), 9, CLOSING)).isEqualTo("\u00e9\u00e9\n\n(cut)");
        assertThat(BodyFit.fit("a", List.of(decomposed), 10, CLOSING)).isEqualTo("a\n\n\u00e9");
        String content = BodyFit.fit("x", List.of("Missing: n" + decomposed + ".txt"), 100, CLOSING);
        assertThat(Normalizer.isNormalized(content, Normalizer.Form.NFC)).isTrue();
    }

    @Test
    @DisplayName("S12-AC-26: the lines come after the body and an empty line, one per line")
    void s12Ac26_linesAfterBody() {
        assertThat(BodyFit.fit("hello", List.of("L1", "L2"), 100, CLOSING)).isEqualTo("hello\n\nL1\nL2");
        assertThat(BodyFit.fit("hello", List.of("L1"), 9, CLOSING)).isEqualTo("hello\n\nL1");
    }

    @Test
    @DisplayName("S12-AC-26: the lines count in the limit; the body is cut to leave room for them")
    void s12Ac26_linesCountInTheLimit() {
        // whole: 10 + 2 + 4 = 16 > 15; closing and lines take 5 + 2 + 4, the empty line before the closing 2.
        assertThat(BodyFit.fit("abcdefghij", List.of("x: f"), 15, CLOSING)).isEqualTo("ab\n\n(cut)\n\nx: f");
        assertThat(BodyFit.fit("abcdefghij", List.of("x: f"), 16, CLOSING)).isEqualTo("abcdefghij\n\nx: f");
        assertThat(BodyFit.fit("abcdefghij", List.of("x: f", "y: g"), 20, CLOSING))
                .isEqualTo("ab\n\n(cut)\n\nx: f\ny: g");
    }

    @Test
    @DisplayName("S12-AC-26: when the lines alone do not fit, the body goes entirely, and the last lines too")
    void s12Ac26_linesAloneDoNotFit() {
        // The two lines alone (5 + 1 + 5 = 11) are over 10: the body goes entirely, and both lines with it.
        assertThat(BodyFit.fit("abcdefghij", List.of("11111", "22222"), 10, CLOSING)).isEqualTo("(cut)");
    }

    @Test
    @DisplayName("S12-AC-26: the last lines that do not fit go, and the body gets what room is left")
    void s12Ac26_linesThatDoNotFit() {
        String body = "abcdefghij";
        List<String> lines = List.of("11111", "22222");
        assertThat(BodyFit.fit(body, lines, 12, CLOSING)).isEqualTo("(cut)\n\n11111");
        assertThat(BodyFit.fit(body, lines, 18, CLOSING)).isEqualTo("(cut)\n\n11111\n22222");
        assertThat(BodyFit.fit(body, lines, 22, CLOSING)).isEqualTo("ab\n\n(cut)\n\n11111\n22222");
        assertThat(BodyFit.fit("abc", List.of("11111"), 5, CLOSING)).isEqualTo("(cut)");
    }

    @Test
    @DisplayName("S12-AC-24, 26: the content is never over the limit")
    void s12Ac24_neverOverTheLimit() {
        String body = "Line one.\n\nLine  two with spaces   \n" + "\ud83d\ude00".repeat(30) + " e\u0301nd";
        List<String> lines = List.of("Missing: a.pdf (empty file)", "Missing: b.zip (over 10 MB)");
        for (int limit = CLOSING.length(); limit < 200; limit++) {
            String content = BodyFit.fit(body, lines, limit, CLOSING);
            assertThat(length(content)).as("limit %d", limit).isLessThanOrEqualTo(limit);
            if (!content.equals(Normalizer.normalize(body, Normalizer.Form.NFC) + "\n\n" + String.join("\n", lines))) {
                assertThat(content).as("limit %d", limit).contains(CLOSING);
            }
        }
    }
}
