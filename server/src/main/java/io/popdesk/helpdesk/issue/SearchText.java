package io.popdesk.helpdesk.issue;

import org.commonmark.node.Block;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;

/**
 * The text the issue search looks in (ISSUE-5, S3-AC-12, ADR D-56), stored in
 * {@code issue.search_text}: the title, a line break, and the content as it is shown.
 *
 * <p>The content is parsed as Markdown and only its text is kept: the text of emphasis, headings,
 * list items, quotes, links, and image descriptions, and what is inside code. Markdown marks, the
 * addresses and titles of links and images, and the language of a code fence are left out. An address
 * written as text and raw HTML are kept as written. Tables and strikethrough are not parsed (no
 * extensions), so their marks stay as text. No HTML is made.
 *
 * <p>The migration that filled the issues of older versions has its own copy of this
 * ({@code db.migration.V10__issue_search_text}), which stays as it was released; a unit test checks
 * that the two agree today.
 */
final class SearchText {

    /** A parser keeps no state between calls and is used by every request. */
    private static final Parser PARSER = Parser.builder().build();

    private SearchText() {}

    static String of(String title, String content) {
        return title + "\n" + shown(content);
    }

    /**
     * The text of {@code markdown} without its marks, a line break after each block. The tree is walked
     * in a loop, not by recursion: nesting as deep as the content is long must not overflow the stack.
     */
    static String shown(String markdown) {
        StringBuilder out = new StringBuilder(markdown.length());
        Node root = PARSER.parse(markdown);
        Node node = root;
        while (true) {
            enter(node, out);
            Node child = node.getFirstChild();
            if (child != null) {
                node = child;
                continue;
            }
            while (true) {
                leave(node, out);
                if (node == root) {
                    return out.toString();
                }
                Node next = node.getNext();
                if (next != null) {
                    node = next;
                    break;
                }
                node = node.getParent();
            }
        }
    }

    private static void enter(Node node, StringBuilder out) {
        switch (node) {
            case Text text -> out.append(text.getLiteral());
            case Code code -> out.append(code.getLiteral());
            case FencedCodeBlock code -> out.append(code.getLiteral());
            case IndentedCodeBlock code -> out.append(code.getLiteral());
            case HtmlInline html -> out.append(html.getLiteral());
            case HtmlBlock html -> out.append(html.getLiteral());
            case SoftLineBreak lineBreak -> out.append('\n');
            case HardLineBreak lineBreak -> out.append('\n');
            default -> { }
        }
    }

    /** Ends a block with a line break, so that the last word of one does not join the first of the next. */
    private static void leave(Node node, StringBuilder out) {
        if (node instanceof Block && !out.isEmpty() && out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
    }
}
