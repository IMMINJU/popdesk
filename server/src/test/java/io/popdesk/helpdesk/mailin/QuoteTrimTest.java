package io.popdesk.helpdesk.mailin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The quoted earlier mail taken off a reply that becomes a comment (S12-AC-23, MAILIN-4, acceptance.md 5절 9).
 * Written from the Javadoc of {@link QuoteTrim} and the acceptance text alone.
 */
class QuoteTrimTest {

    @Test
    @DisplayName("S12-AC-23: null is the empty text")
    void s12Ac23_null() {
        assertThat(QuoteTrim.trim(null)).isEmpty();
        assertThat(QuoteTrim.trim("")).isEmpty();
    }

    @Test
    @DisplayName("S12-AC-23: a body without a separator is kept, without its empty lines at the end")
    void s12Ac23_noSeparatorKeepsBody() {
        assertThat(QuoteTrim.trim("Thanks, it works.")).isEqualTo("Thanks, it works.");
        assertThat(QuoteTrim.trim("  First\n\nSecond\n\n\n")).isEqualTo("  First\n\nSecond");
        assertThat(QuoteTrim.trim("a\n  \n\t\n")).isEqualTo("a");
        assertThat(QuoteTrim.trim("\n\n")).isEmpty();
    }

    @Test
    @DisplayName("S12-AC-23: from an On ... wrote: line to the end is taken off")
    void s12Ac23_onWrote() {
        assertThat(QuoteTrim.trim("Thanks\n\nOn Mon, Oct 19, 2026 at 2:00 PM Kim <kim@example.com> wrote:\n> old\n"))
                .isEqualTo("Thanks");
        assertThat(QuoteTrim.trim("Thanks\n   On Monday Kim wrote:   \nnot quoted text\nmore"))
                .isEqualTo("Thanks");
        assertThat(QuoteTrim.trim("On Mon Kim wrote:\n> old")).isEmpty();
    }

    @Test
    @DisplayName("S12-AC-23: a line that ends with 작성: is a separator, 님이 작성: too")
    void s12Ac23_koreanSeparator() {
        assertThat(QuoteTrim.trim("감사합니다\n\n2026년 10월 19일 (월) 오후 2:00, 홍길동 <hong@example.com>님이 작성:\n> 이전"))
                .isEqualTo("감사합니다");
        assertThat(QuoteTrim.trim("감사합니다\n홍길동 작성:\n이전 메일")).isEqualTo("감사합니다");
        assertThat(QuoteTrim.trim("감사합니다\n  작성:  \n이전")).isEqualTo("감사합니다");
    }

    @Test
    @DisplayName("S12-AC-23: -----Original Message----- and -----원본 메시지----- in any letter case")
    void s12Ac23_originalMessage() {
        for (String line : List.of("-----Original Message-----", "-----original message-----",
                "-----ORIGINAL MESSAGE-----", "  -----Original Message-----  ", "-----원본 메시지-----")) {
            assertThat(QuoteTrim.trim("Reply\n\n" + line + "\nFrom: kim\nSubject: x")).as(line).isEqualTo("Reply");
        }
    }

    @Test
    @DisplayName("S12-AC-23: the > lines at the end are taken off, with empty lines among and after them")
    void s12Ac23_trailingQuoteLines() {
        assertThat(QuoteTrim.trim("Reply\n> a\n>\n> b")).isEqualTo("Reply");
        assertThat(QuoteTrim.trim("Reply\n\n> a\n\n  > b\n\n")).isEqualTo("Reply");
        assertThat(QuoteTrim.trim("> a\n> b")).isEmpty();
        assertThat(QuoteTrim.trim("Reply\n>")).isEqualTo("Reply");
    }

    @Test
    @DisplayName("S12-AC-23: > lines followed by text are not at the end and are kept")
    void s12Ac23_quoteLinesInTheMiddleAreKept() {
        assertThat(QuoteTrim.trim("> your question\nMy answer")).isEqualTo("> your question\nMy answer");
        assertThat(QuoteTrim.trim("a\n> q\nb\n")).isEqualTo("a\n> q\nb");
        assertThat(QuoteTrim.trim("a\n> q1\nb\n> q2")).isEqualTo("a\n> q1\nb");
    }

    @Test
    @DisplayName("S12-AC-23: the first separator counts, also before > lines at the end")
    void s12Ac23_firstSeparatorCounts() {
        assertThat(QuoteTrim.trim("a\n> q1\nb\nOn Mon Kim wrote:\n> q2")).isEqualTo("a\n> q1\nb");
        assertThat(QuoteTrim.trim("a\n-----Original Message-----\nb\nOn Mon Kim wrote:\nc")).isEqualTo("a");
    }

    @Test
    @DisplayName("S12-AC-23: lines that only look like a separator are kept")
    void s12Ac23_lookalikesAreKept() {
        for (String body : List.of("Kim wrote: hello", "Someone wrote:", "Online, we wrote:", "On it, I wrote: x",
                "작성: 홍길동", "--Original Message--", "-----Original Message----- see below",
                "Quote: > not at start")) {
            assertThat(QuoteTrim.trim(body)).as(body).isEqualTo(body);
        }
    }

    @Test
    @DisplayName("S12-AC-23: \\r before \\n is dropped")
    void s12Ac23_crlf() {
        assertThat(QuoteTrim.trim("a\r\nb\r\n\r\nOn Mon Kim wrote:\r\n> q\r\n")).isEqualTo("a\nb");
        assertThat(QuoteTrim.trim("a\r\nb")).isEqualTo("a\nb");
    }
}
