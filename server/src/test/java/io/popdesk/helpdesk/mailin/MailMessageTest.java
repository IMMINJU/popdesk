package io.popdesk.helpdesk.mailin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two pure helpers of {@link MailMessage}: the issue title made from a decoded subject (S12-AC-20, ISSUE-2) and
 * the file name kept for an attachment (S12-AC-25). Written from their Javadoc and the acceptance text alone.
 */
class MailMessageTest {

    /** The character of this code point, so the source holds no control or combining character. */
    private static String c(int codePoint) {
        return Character.toString(codePoint);
    }

    // ---- title (S12-AC-20) ----

    @Test
    @DisplayName("S12-AC-20: null is the empty title; blank is empty too")
    void s12Ac20_nullTitle() {
        assertThat(MailMessage.title(null)).isEmpty();
        assertThat(MailMessage.title("")).isEmpty();
        assertThat(MailMessage.title("  \t ")).isEmpty();
        assertThat(MailMessage.title("\r\n")).isEmpty();
    }

    @Test
    @DisplayName("S12-AC-20: the subject with the spaces around it cut")
    void s12Ac20_trimmed() {
        assertThat(MailMessage.title("  The printer is broken  ")).isEqualTo("The printer is broken");
        assertThat(MailMessage.title("Re: [ACME-7] x")).isEqualTo("Re: [ACME-7] x");
    }

    @Test
    @DisplayName("S12-AC-20: line breaks and C0, C1, U+2028 and U+2029 are spaces")
    void s12Ac20_controlsAreSpaces() {
        for (int cp : new int[] {0x00, 0x01, 0x09, 0x0a, 0x0d, 0x1b, 0x1f, 0x7f, 0x80, 0x85, 0x9f, 0x2028, 0x2029}) {
            assertThat(MailMessage.title("a" + c(cp) + "b")).as("U+%04X", cp).isEqualTo("a b");
        }
    }

    @Test
    @DisplayName("S12-AC-20: characters next to the control ranges are kept")
    void s12Ac20_otherCharactersKept() {
        for (int cp : new int[] {0x20, 0x21, 0x7e, 0xa0, 0xa1, 0x2027, 0x202a, 0x200b}) {
            String title = "a" + c(cp) + "b";
            assertThat(MailMessage.title(title)).as("U+%04X", cp).isEqualTo(title);
        }
    }

    @Test
    @DisplayName("S12-AC-20: controls at the ends become spaces and are cut")
    void s12Ac20_controlsAtEndsCut() {
        assertThat(MailMessage.title(c(0x0d) + c(0x0a) + "Hello" + c(0x85) + c(0x2028))).isEqualTo("Hello");
    }

    @Test
    @DisplayName("S12-AC-20: the title is NFC")
    void s12Ac20_nfc() {
        assertThat(MailMessage.title("Caf" + "e" + c(0x301))).isEqualTo("Caf" + c(0xe9));
    }

    @Test
    @DisplayName("S12-AC-20: at most 200 code points")
    void s12Ac20_atMost200() {
        assertThat(MailMessage.title("a".repeat(200))).isEqualTo("a".repeat(200));
        assertThat(MailMessage.title("a".repeat(201))).isEqualTo("a".repeat(200));
        assertThat(MailMessage.title("a".repeat(199) + "bc")).isEqualTo("a".repeat(199) + "b");
        assertThat(MailMessage.title("   " + "a".repeat(200) + "   ")).isEqualTo("a".repeat(200));
        String grin = c(0x1f600);
        assertThat(MailMessage.title(grin.repeat(200))).isEqualTo(grin.repeat(200));
        assertThat(MailMessage.title(grin.repeat(250))).isEqualTo(grin.repeat(200));
        String decomposed = "e" + c(0x301);
        assertThat(MailMessage.title(decomposed.repeat(200))).isEqualTo(c(0xe9).repeat(200));
    }

    // ---- fileName (S12-AC-25) ----

    @Test
    @DisplayName("S12-AC-25: a plain name is kept")
    void s12Ac25_plainName() {
        assertThat(MailMessage.fileName("report.pdf")).isEqualTo("report.pdf");
        assertThat(MailMessage.fileName("my report (final).pdf")).isEqualTo("my report (final).pdf");
    }

    @Test
    @DisplayName("S12-AC-25: encoded words are decoded")
    void s12Ac25_encodedWords() {
        assertThat(MailMessage.fileName("=?UTF-8?Q?caf=C3=A9.txt?=")).isEqualTo("caf" + c(0xe9) + ".txt");
        assertThat(MailMessage.fileName("=?UTF-8?B?7ZWc6riALnR4dA==?=")).isEqualTo("\ud55c\uae00.txt");
    }

    @Test
    @DisplayName("S12-AC-25: everything up to the last / or \\ is taken off")
    void s12Ac25_pathTakenOff() {
        assertThat(MailMessage.fileName("../../etc/passwd")).isEqualTo("passwd");
        assertThat(MailMessage.fileName("C:\\Users\\kim\\a.txt")).isEqualTo("a.txt");
        assertThat(MailMessage.fileName("a/b\\c.txt")).isEqualTo("c.txt");
        assertThat(MailMessage.fileName("a\\b/c.txt")).isEqualTo("c.txt");
        assertThat(MailMessage.fileName("/root.txt")).isEqualTo("root.txt");
        assertThat(MailMessage.fileName("=?UTF-8?Q?dir/a.txt?=")).isEqualTo("a.txt");
    }

    @Test
    @DisplayName("S12-AC-25: control characters are taken out, also ones that were encoded")
    void s12Ac25_controlsTakenOut() {
        for (int cp : new int[] {0x00, 0x09, 0x0a, 0x0d, 0x1f, 0x7f, 0x85, 0x9f, 0x2028, 0x2029}) {
            assertThat(MailMessage.fileName("a" + c(cp) + "b.txt")).as("U+%04X", cp).isEqualTo("ab.txt");
        }
        assertThat(MailMessage.fileName("=?UTF-8?Q?a=00b=0A.txt?=")).isEqualTo("ab.txt");
    }

    @Test
    @DisplayName("S12-AC-25: bidirectional controls U+202A to U+202E and U+2066 to U+2069 are taken out")
    void s12Ac25_bidiTakenOut() {
        for (int cp : new int[] {0x202a, 0x202b, 0x202c, 0x202d, 0x202e, 0x2066, 0x2067, 0x2068, 0x2069}) {
            assertThat(MailMessage.fileName("invoice" + c(cp) + "fdp.exe")).as("U+%04X", cp)
                    .isEqualTo("invoicefdp.exe");
        }
        assertThat(MailMessage.fileName("=?UTF-8?Q?a=E2=80=AEb.txt?=")).isEqualTo("ab.txt");
    }

    @Test
    @DisplayName("S12-AC-25: characters next to the removed ranges are kept")
    void s12Ac25_neighboursKept() {
        for (int cp : new int[] {0x20, 0x7e, 0xa0, 0x2027, 0x202f, 0x2065, 0x206a}) {
            String name = "a" + c(cp) + "b.txt";
            assertThat(MailMessage.fileName(name)).as("U+%04X", cp).isEqualTo(name);
        }
    }

    @Test
    @DisplayName("S12-AC-25: the name is NFC")
    void s12Ac25_nfc() {
        assertThat(MailMessage.fileName("e" + c(0x301) + ".txt")).isEqualTo(c(0xe9) + ".txt");
        assertThat(MailMessage.fileName("=?UTF-8?Q?e=CC=81.txt?=")).isEqualTo(c(0xe9) + ".txt");
    }

    @Test
    @DisplayName("S12-AC-25: empty when nothing is left")
    void s12Ac25_emptyWhenNothingLeft() {
        assertThat(MailMessage.fileName("")).isEmpty();
        assertThat(MailMessage.fileName("dir/")).isEmpty();
        assertThat(MailMessage.fileName("a\\")).isEmpty();
        assertThat(MailMessage.fileName(c(0x202e) + c(0x00))).isEmpty();
        assertThat(MailMessage.fileName("=?UTF-8?Q?=E2=80=AE?=")).isEmpty();
    }
}
