package io.popdesk.helpdesk.mailin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The text of an HTML body for an email without a {@code text/plain} part (S12-AC-22, MAILIN-4). Written from the
 * Javadoc of {@link HtmlText} and the acceptance text alone.
 */
class HtmlTextTest {

    /** The lines that hold text, in order. */
    private static List<String> lines(String text) {
        return text.lines().filter(l -> !l.isEmpty()).toList();
    }

    @Test
    @DisplayName("S12-AC-22: null or empty HTML is the empty text")
    void s12Ac22_nullOrEmpty() {
        assertThat(HtmlText.text(null)).isEmpty();
        assertThat(HtmlText.text("")).isEmpty();
        assertThat(HtmlText.text("   \n\t ")).isEmpty();
        assertThat(HtmlText.text("<html><body></body></html>")).isEmpty();
    }

    @Test
    @DisplayName("S12-AC-22: text without elements is kept")
    void s12Ac22_plainText() {
        assertThat(HtmlText.text("Hello there")).isEqualTo("Hello there");
    }

    @Test
    @DisplayName("S12-AC-22: head, script, style and the like are dropped")
    void s12Ac22_dropsHeadScriptStyle() {
        String html = "<html><head><title>Title</title><style>p { color: red }</style><script>var a = 1;</script>"
                + "</head><body><p>Hi</p><script>alert('x')</script><style>.b { }</style>"
                + "<noscript>Turn on scripts</noscript><template><p>Template</p></template></body></html>";
        assertThat(HtmlText.text(html)).isEqualTo("Hi");
    }

    @Test
    @DisplayName("S12-AC-22: <br> is a line break")
    void s12Ac22_brIsLineBreak() {
        assertThat(HtmlText.text("a<br>b")).isEqualTo("a\nb");
        assertThat(HtmlText.text("a<br/>b<br />c")).isEqualTo("a\nb\nc");
    }

    @Test
    @DisplayName("S12-AC-22: block elements begin new lines")
    void s12Ac22_blocksAreLineBreaks() {
        assertThat(lines(HtmlText.text("<p>a</p><p>b</p>"))).containsExactly("a", "b");
        assertThat(lines(HtmlText.text("<div>a</div>b"))).containsExactly("a", "b");
        assertThat(lines(HtmlText.text("a<div>b</div>"))).containsExactly("a", "b");
        assertThat(lines(HtmlText.text("<ul><li>one</li><li>two</li></ul>"))).containsExactly("one", "two");
        assertThat(lines(HtmlText.text("<h1>Title</h1>text<blockquote>quoted</blockquote>after")))
                .containsExactly("Title", "text", "quoted", "after");
        assertThat(lines(HtmlText.text("<table><tr><td>a</td></tr><tr><td>b</td></tr></table>")))
                .containsExactly("a", "b");
    }

    @Test
    @DisplayName("S12-AC-22: inline elements do not break the line")
    void s12Ac22_inlineElementsStayOnTheLine() {
        assertThat(HtmlText.text("<p>a <b>bold</b> and <i>it</i><span>alic</span></p>")).isEqualTo("a bold and italic");
    }

    @Test
    @DisplayName("S12-AC-22: cells of a row are kept apart")
    void s12Ac22_cellsAreKeptApart() {
        assertThat(HtmlText.text("<table><tr><td>a</td><td>b</td></tr></table>")).isEqualTo("a b");
        assertThat(HtmlText.text("<table><tr><th>x</th><th>y</th></tr></table>")).isEqualTo("x y");
    }

    @Test
    @DisplayName("S12-AC-22: a link is text (address) when its address is not its text")
    void s12Ac22_linkWithAddress() {
        assertThat(HtmlText.text("<p>See <a href=\"https://example.com/a\">the page</a>.</p>"))
                .isEqualTo("See the page (https://example.com/a).");
        assertThat(HtmlText.text("<a href=\"mailto:kim@example.com\">kim@example.com</a>"))
                .isEqualTo("kim@example.com (mailto:kim@example.com)");
    }

    @Test
    @DisplayName("S12-AC-22: a link whose address is its text, or without an address, is its text")
    void s12Ac22_linkWithoutOtherAddress() {
        assertThat(HtmlText.text("<a href=\"https://example.com/a\">https://example.com/a</a>"))
                .isEqualTo("https://example.com/a");
        assertThat(HtmlText.text("<a name=\"top\">anchor</a>")).isEqualTo("anchor");
        assertThat(HtmlText.text("<a href=\"\">empty</a>")).isEqualTo("empty");
    }

    @Test
    @DisplayName("S12-AC-22: character references are read")
    void s12Ac22_characterReferences() {
        assertThat(HtmlText.text("<p>Tom &amp; Jerry &lt;3 &#x263A; &eacute; &#233; &quot;q&quot;</p>"))
                .isEqualTo("Tom & Jerry <3 \u263a \u00e9 \u00e9 \"q\"");
    }

    @Test
    @DisplayName("S12-AC-22: runs of white space in text are one space")
    void s12Ac22_whiteSpaceRuns() {
        assertThat(HtmlText.text("<p>a   \n\t  b</p>")).isEqualTo("a b");
        assertThat(HtmlText.text("a\n\n\nb")).isEqualTo("a b");
    }

    @Test
    @DisplayName("S12-AC-22: white space in pre is kept, also in elements inside it; lines are still trimmed")
    void s12Ac22_preKeepsWhiteSpace() {
        assertThat(HtmlText.text("<pre>a   b\n    c</pre>")).isEqualTo("a   b\nc");
        assertThat(HtmlText.text("<pre><code>x   y</code></pre>")).isEqualTo("x   y");
        assertThat(lines(HtmlText.text("<div>x   y</div><pre>p   q</pre>"))).containsExactly("x y", "p   q");
    }

    @Test
    @DisplayName("S12-AC-22: each line trimmed, at most one empty line in a row, none at the start or the end")
    void s12Ac22_tidyLines() {
        assertThat(HtmlText.text("<div>   a   </div>")).isEqualTo("a");
        assertThat(HtmlText.text("a<br><br>b")).isEqualTo("a\n\nb");
        assertThat(HtmlText.text("a<br><br><br><br><br>b")).isEqualTo("a\n\nb");
        assertThat(HtmlText.text("<br><br>a<br><br>")).isEqualTo("a");
        assertThat(HtmlText.text("<p>a</p>")).isEqualTo("a");
        assertThat(HtmlText.text("a<br> <br>\t<br>b")).isEqualTo("a\n\nb");
    }

    @Test
    @DisplayName("S12-AC-22: broken HTML is read as browsers do")
    void s12Ac22_brokenHtml() {
        assertThat(HtmlText.text("<div>unclosed")).isEqualTo("unclosed");
        assertThat(HtmlText.text("a < b & c")).isEqualTo("a < b & c");
        assertThat(HtmlText.text("<b>bold <i>both</b> after")).isEqualTo("bold both after");
        assertThat(lines(HtmlText.text("<p>a<p>b"))).containsExactly("a", "b");
        assertThat(HtmlText.text("</p></div>text")).isEqualTo("text");
    }
}
