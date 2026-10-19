package io.popdesk.helpdesk.mailin;

import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

/**
 * The text of an HTML body, for an email without a {@code text/plain} part (S12-AC-22, MAILIN-4). Pure; the HTML is
 * read by jsoup (D-67), which takes broken HTML and character references as browsers do.
 */
final class HtmlText {

    /** Elements whose start and end begin a new line. */
    private static final Set<String> BLOCKS = Set.of("address", "article", "aside", "blockquote", "dd", "div", "dl",
            "dt", "fieldset", "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header",
            "hr", "li", "main", "nav", "ol", "p", "pre", "section", "table", "tbody", "thead", "tfoot", "tr", "ul");

    /** Elements whose text is no part of what the email says. */
    private static final String DROPPED = "head, script, style, title, noscript, template";

    private HtmlText() {}

    /**
     * The HTML as text: {@code head}, {@code script}, {@code style}, and the like dropped; block elements and
     * {@code <br>} as line breaks; a link as {@code text (address)} when its address is not its text; character
     * references read; runs of white space in text as one space, except in {@code pre}; each line trimmed, at most
     * one empty line in a row, and none at the start or the end.
     *
     * @param html the HTML as decoded; null or empty gives the empty text
     */
    static String text(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        Document document = Jsoup.parse(html);
        document.select(DROPPED).remove();
        StringBuilder out = new StringBuilder();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode text) {
                    out.append(inPre(text) ? text.getWholeText() : text.getWholeText().replaceAll("\\s+", " "));
                } else if (node instanceof Element e) {
                    if (e.normalName().equals("br")) {
                        out.append('\n');
                    } else if (BLOCKS.contains(e.normalName())) {
                        out.append('\n');
                    } else if (e.normalName().equals("td") || e.normalName().equals("th")) {
                        out.append(' ');
                    }
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (!(node instanceof Element e)) {
                    return;
                }
                if (BLOCKS.contains(e.normalName())) {
                    out.append('\n');
                } else if (e.normalName().equals("a")) {
                    String href = e.attr("href").strip();
                    if (!href.isEmpty() && !href.startsWith("#") && !href.equals(e.text().strip())) {
                        out.append(" (").append(href).append(')');
                    }
                }
            }
        }, document.body());
        return tidy(out.toString());
    }

    private static boolean inPre(TextNode text) {
        for (Element e = text.parentElement(); e != null; e = e.parentElement()) {
            if (e.normalName().equals("pre")) {
                return true;
            }
        }
        return false;
    }

    /** Each line trimmed, no more than one empty line in a row, none at the ends. */
    private static String tidy(String text) {
        StringBuilder out = new StringBuilder();
        int empty = 0;
        for (String line : text.split("\n", -1)) {
            String l = line.strip();
            if (l.isEmpty()) {
                empty++;
                continue;
            }
            if (!out.isEmpty()) {
                out.append(empty > 0 ? "\n\n" : "\n");
            }
            out.append(l);
            empty = 0;
        }
        return out.toString();
    }
}
