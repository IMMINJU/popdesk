package io.popdesk.helpdesk.mailin;

import io.popdesk.TextInput;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * Fits the content of an issue or comment made from an email in its limit (S12-AC-24, S12-AC-26, MAILIN-4): the body,
 * then the lines about files left out. Pure. Lengths are in code points of the NFC text, as {@link TextInput} counts.
 */
final class BodyFit {

    private BodyFit() {}

    /**
     * The content: {@code body}, then, when it was cut, an empty line and {@code closingLine}, then an empty line and
     * {@code lines} one per line. When all of it is over {@code limit}, the body is cut from its end, at a code point,
     * until the whole fits; its spaces and line breaks at the cut are dropped. When the lines alone do not fit, the
     * body goes entirely and the last lines that do not fit go too.
     *
     * @param body the body, NFC or not; never null; may be empty
     * @param lines lines to add at the end, each without a line break; may be empty
     * @param limit the most code points of the content, at least the length of {@code closingLine}
     * @param closingLine the line that says the body was cut
     * @return the content, NFC, at most {@code limit} code points
     */
    static String fit(String body, List<String> lines, int limit, String closingLine) {
        String text = Normalizer.normalize(body, Normalizer.Form.NFC);
        List<String> added = new ArrayList<>();
        for (String line : lines) {
            added.add(Normalizer.normalize(line, Normalizer.Form.NFC));
        }
        String whole = join(text, false, closingLine, added);
        if (TextInput.length(whole) <= limit) {
            return whole;
        }
        boolean linesFit = true;
        while (!added.isEmpty() && TextInput.length(join("", true, closingLine, added)) > limit) {
            added.removeLast();
            linesFit = false;
        }
        int room = limit - TextInput.length(join("", true, closingLine, added)) - 2;
        String kept = !linesFit || room <= 0 ? "" : cut(text, room).stripTrailing();
        return join(kept, true, closingLine, added);
    }

    private static String join(String body, boolean cut, String closingLine, List<String> lines) {
        StringBuilder out = new StringBuilder(body);
        if (cut) {
            if (!out.isEmpty()) {
                out.append("\n\n");
            }
            out.append(closingLine);
        }
        if (!lines.isEmpty()) {
            if (!out.isEmpty()) {
                out.append("\n\n");
            }
            out.append(String.join("\n", lines));
        }
        return out.toString();
    }

    /** The first {@code codePoints} code points of the text. */
    private static String cut(String text, int codePoints) {
        if (TextInput.length(text) <= codePoints) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, codePoints));
    }
}
