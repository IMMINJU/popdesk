package io.popdesk.helpdesk.mailin;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Takes the quoted earlier mail off a reply that becomes a comment (S12-AC-23, MAILIN-4). Pure. A new issue keeps its
 * quote: a forwarded mail is often what the issue is about.
 */
final class QuoteTrim {

    /** {@code On Mon, Oct 19, 2026 at 2:00 PM Someone <a@b> wrote:} */
    private static final Pattern ON_WROTE = Pattern.compile("(?i)on\\b.*\\bwrote:");

    private static final List<String> ORIGINAL = List.of("-----original message-----", "-----원본 메시지-----");

    private QuoteTrim() {}

    /**
     * The body without its quote: from the first separator line to the end, then the empty lines at the end. A
     * separator line, trimmed, is one of: {@code On … wrote:}; one that ends with {@code 작성:} (Korean mail programs,
     * {@code … 님이 작성:} too); {@code -----Original Message-----} or {@code -----원본 메시지-----} in any letter case;
     * or the first of the {@code >} lines at the end, with only empty lines among and after them. Without one the body
     * is kept, without its empty lines at the end.
     *
     * @param body the reply's text, lines split at {@code \n} (a {@code \r} before it is dropped); null gives the
     *     empty text
     */
    static String trim(String body) {
        if (body == null) {
            return "";
        }
        List<String> lines = Arrays.asList(body.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
        int cut = lines.size();
        for (int i = 0; i < lines.size(); i++) {
            if (separator(lines.get(i).strip())) {
                cut = i;
                break;
            }
        }
        int quoteStart = trailingQuote(lines);
        cut = Math.min(cut, quoteStart);
        while (cut > 0 && lines.get(cut - 1).isBlank()) {
            cut--;
        }
        return String.join("\n", lines.subList(0, cut)).stripTrailing();
    }

    private static boolean separator(String line) {
        return ON_WROTE.matcher(line).matches() || line.endsWith("작성:")
                || ORIGINAL.contains(line.toLowerCase(Locale.ROOT));
    }

    /** The first line of the {@code >} lines at the end (empty lines among them); the size when there are none. */
    private static int trailingQuote(List<String> lines) {
        int start = lines.size();
        for (int i = lines.size() - 1; i >= 0; i--) {
            String l = lines.get(i).strip();
            if (l.startsWith(">")) {
                start = i;
            } else if (!l.isEmpty()) {
                break;
            }
        }
        return start;
    }
}
