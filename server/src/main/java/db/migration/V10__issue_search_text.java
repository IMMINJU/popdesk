package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Fills {@code issue.search_text} for the issues that are there (S3-AC-89): the title, a line break,
 * and the text the content shows without its Markdown marks (ADR D-56). V11 builds the index after
 * this, which is far faster than filling under the index.
 *
 * <p>The way the text is taken out of the content is written here and calls nothing of the app: a
 * released migration never changes, whatever the app does later (ADR D-24). When this was written it
 * gave the same text as the app ({@code io.popdesk.helpdesk.issue.SearchText}).
 *
 * <p>Flyway creates this class itself and runs it in one transaction: if the app dies here nothing is
 * kept and the next start does it all again. The rows are read with a cursor and written a batch at a
 * time with one statement, so the heap stays small whatever the number of issues.
 */
public class V10__issue_search_text extends BaseJavaMigration {

    private static final int BATCH = 500;

    private static final Parser PARSER = Parser.builder().build();

    @Override
    public void migrate(Context context) throws SQLException {
        Connection c = context.getConnection();
        try (PreparedStatement read = c.prepareStatement("select id, title, content from issue");
                PreparedStatement write = c.prepareStatement("update issue i set search_text = u.search_text "
                        + "from unnest(?::uuid[], ?::text[]) as u(id, search_text) where i.id = u.id")) {
            read.setFetchSize(BATCH);
            List<UUID> ids = new ArrayList<>(BATCH);
            List<String> texts = new ArrayList<>(BATCH);
            try (ResultSet rs = read.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getObject(1, UUID.class));
                    texts.add(searchText(rs.getString(2), rs.getString(3)));
                    if (ids.size() == BATCH) {
                        write(c, write, ids, texts);
                    }
                }
            }
            if (!ids.isEmpty()) {
                write(c, write, ids, texts);
            }
        }
    }

    private static void write(Connection c, PreparedStatement write, List<UUID> ids, List<String> texts)
            throws SQLException {
        write.setArray(1, c.createArrayOf("uuid", ids.toArray(UUID[]::new)));
        write.setArray(2, c.createArrayOf("text", texts.toArray(String[]::new)));
        write.executeUpdate();
        ids.clear();
        texts.clear();
    }

    /** The title, a line break, and the text of the content. */
    public static String searchText(String title, String content) {
        return title + "\n" + shown(content);
    }

    /**
     * The text of {@code markdown} without its marks, a line break after each block: what is inside
     * emphasis, headings, list items, quotes, links, image descriptions, and code, and raw HTML as
     * written. Walked in a loop, so deep nesting does not overflow the stack.
     */
    private static String shown(String markdown) {
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
                if (node instanceof Block && !out.isEmpty() && out.charAt(out.length() - 1) != '\n') {
                    out.append('\n');
                }
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
        if (node instanceof Text text) {
            out.append(text.getLiteral());
        } else if (node instanceof Code code) {
            out.append(code.getLiteral());
        } else if (node instanceof FencedCodeBlock code) {
            out.append(code.getLiteral());
        } else if (node instanceof IndentedCodeBlock code) {
            out.append(code.getLiteral());
        } else if (node instanceof HtmlInline html) {
            out.append(html.getLiteral());
        } else if (node instanceof HtmlBlock html) {
            out.append(html.getLiteral());
        } else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
            out.append('\n');
        }
    }
}
