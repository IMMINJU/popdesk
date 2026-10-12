package io.popdesk.helpdesk.issue;

import static org.assertj.core.api.Assertions.assertThat;

import db.migration.V10__issue_search_text;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The text the issue search looks in ({@link SearchText}): what of the Markdown stays, that input
 * nested as deep as the content may be long is taken, and that the migration which filled older
 * issues gives the same text.
 */
class SearchTextTest {

    /** The longest content an issue takes (ISSUE-2). */
    private static final int CONTENT_MAX = 10_000;

    static Stream<Arguments> kept() {
        return Stream.of(
                Arguments.of("bold and italic", "a **bold** and *slanted* and __under__ word", "a bold and slanted and under word\n"),
                Arguments.of("headings", "# First\n\nSecond\n======\n", "First\nSecond\n"),
                Arguments.of("lists", "- one\n- two\n\n1. three\n", "one\ntwo\nthree\n"),
                Arguments.of("a task mark stays", "- [ ] todo\n", "[ ] todo\n"),
                Arguments.of("link", "see [the manual](https://host.example/path \"Tip\") now", "see the manual now\n"),
                Arguments.of("reference link", "see [the manual][m]\n\n[m]: https://host.example/path\n", "see the manual\n"),
                Arguments.of("address written as text", "at <https://host.example/page>.", "at https://host.example/page.\n"),
                Arguments.of("image", "![a printer](attachment:1234)", "a printer\n"),
                Arguments.of("code fence", "```java\nint x = 1;\n```\n", "int x = 1;\n"),
                Arguments.of("tilde fence", "~~~\nplain\n~~~\n", "plain\n"),
                Arguments.of("indented code", "text\n\n    indented\n", "text\nindented\n"),
                Arguments.of("inline code", "run `ls -l` here", "run ls -l here\n"),
                Arguments.of("quote", "> said\n>\n> > twice\n", "said\ntwice\n"),
                Arguments.of("HTML block", "<div class=\"box\">inside</div>\n", "<div class=\"box\">inside</div>\n"),
                Arguments.of("inline HTML", "a <b>bold</b> b", "a <b>bold</b> b\n"),
                Arguments.of("entities", "A &amp; B &copy; &#x41;", "A & B © A\n"),
                Arguments.of("escaped mark", "not \\*bold\\*", "not *bold*\n"),
                Arguments.of("table marks stay", "| a | b |\n|---|---|\n| 1 | 2 |\n", "| a | b |\n|---|---|\n| 1 | 2 |\n"),
                Arguments.of("strikethrough marks stay", "~~gone~~", "~~gone~~\n"),
                Arguments.of("line breaks", "one\ntwo  \nthree", "one\ntwo\nthree\n"),
                Arguments.of("Korean", "**굵게** [문서](https://host.example/주소) `코드`", "굵게 문서 코드\n"),
                Arguments.of("empty", "", ""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("kept")
    @DisplayName("S3-AC-12 the text of emphasis, headings, lists, quotes, links, images, and code stays, raw HTML "
            + "and an address written as text stay as written, and marks, link and image addresses, and the "
            + "language of a fence go")
    void keepsTheShownText(String what, String markdown, String shown) {
        assertThat(SearchText.shown(markdown)).as(what).isEqualTo(shown);
    }

    @Test
    @DisplayName("S3-AC-12 the search text is the title, a line break, and the shown text of the content; the "
            + "title is kept as written")
    void titleComesFirstAsWritten() {
        assertThat(SearchText.of("**Printer** [B2](x)", "It *jams*.")).isEqualTo("**Printer** [B2](x)\nIt jams.\n");
        assertThat(SearchText.of("Only a title", "")).isEqualTo("Only a title\n");
    }

    /** Inputs of 10,000 characters that nest or repeat one mark as often as they can (D-56). */
    static Stream<Arguments> deep() {
        List<Arguments> out = new ArrayList<>();
        for (String unit : List.of(">", "> ", "- ", "* ", "1. ", "[", "![", "[a](", "(", "*a", "_a", "**", "*_", "`",
                "<", "<a", "&", "|", "~", "#", "\\", "[a][", "\t", "  - ", "> - ", "- > ")) {
            out.add(Arguments.of("\"" + unit.replace("\t", "\\t") + "\" repeated",
                    unit.repeat(CONTENT_MAX / unit.length())));
        }
        StringBuilder emphasis = new StringBuilder();
        for (int i = 0; i < CONTENT_MAX / 4; i++) {
            emphasis.append(i % 2 == 0 ? "*" : "_");
        }
        emphasis.append("x");
        for (int i = CONTENT_MAX / 4 - 1; i >= 0; i--) {
            emphasis.append(i % 2 == 0 ? "*" : "_");
        }
        out.add(Arguments.of("emphasis nested 2,500 deep", emphasis.toString()));
        out.add(Arguments.of("links nested in brackets", "[".repeat(3_000) + "a" + "](x)".repeat(1_500)));
        StringBuilder list = new StringBuilder();
        for (int depth = 0; list.length() + depth * 2 + 4 < CONTENT_MAX; depth++) {
            list.append(" ".repeat(depth * 2)).append("- x\n");
        }
        out.add(Arguments.of("a list item inside the one before, as deep as it fits", list.toString()));
        return out.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("deep")
    @DisplayName("D-56 content of 10,000 characters that nests or repeats one Markdown mark is taken without an "
            + "error, on a thread with a small stack, and gives no more text than it had")
    void deeplyNestedContentIsTaken(String what, String markdown) throws InterruptedException {
        assertThat(markdown.length()).as("length of the input").isLessThanOrEqualTo(CONTENT_MAX);
        List<Object> result = new ArrayList<>();
        // 256 KB: a quarter of the default stack of a request thread.
        Thread small = new Thread(null, () -> {
            try {
                result.add(SearchText.shown(markdown));
            } catch (Throwable e) {
                result.add(e);
            }
        }, "search-text-small-stack", 256 * 1024);
        small.start();
        small.join();

        assertThat(result).as(what).hasSize(1);
        assertThat(result.getFirst()).as(what).isInstanceOf(String.class);
        assertThat(((String) result.getFirst()).length()).as("length of the text of %s", what)
                .isLessThanOrEqualTo(markdown.length() + 1);
    }

    @Test
    @DisplayName("D-24 the migration that filled the search text of older issues (V10) gives the same text as the "
            + "app for every input of this test")
    void migrationAgreesWithTheApp() {
        List<String> contents = new ArrayList<>();
        kept().forEach(a -> contents.add((String) a.get()[1]));
        deep().forEach(a -> contents.add((String) a.get()[1]));
        contents.add("Steps to reproduce:\n1. Open it.\n2. See **the error**.\n\n> quoted\n\n<p>raw</p>");

        assertThat(contents).allSatisfy(content -> assertThat(V10__issue_search_text.searchText("A title", content))
                .isEqualTo(SearchText.of("A title", content)));
    }
}
