package io.popdesk.helpdesk.mailin;

import io.popdesk.helpdesk.issue.IssueKey;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The issue key a reply carries in its subject as {@code [KEY]} (S12-AC-16, MAILIN-2). Pure.
 */
final class IssueKeyInSubject {

    /** Text in square brackets, without brackets inside it. */
    private static final Pattern BRACKETS = Pattern.compile("\\[([^\\[\\]]*)\\]");

    private IssueKeyInSubject() {}

    /**
     * The first text in square brackets that is exactly of the key form ({@code [A-Z0-9]{2,10}-[1-9][0-9]{0,8}},
     * {@link IssueKey}) in any letter case, in upper case. Brackets around anything else ({@code [CODE-0]}, ten
     * digits, spaces inside) are passed over.
     *
     * @param subject the decoded subject; null or empty gives empty
     */
    static Optional<IssueKey> key(String subject) {
        if (subject == null) {
            return Optional.empty();
        }
        Matcher m = BRACKETS.matcher(subject);
        while (m.find()) {
            Optional<IssueKey> key = IssueKey.parse(asciiUpper(m.group(1)));
            if (key.isPresent()) {
                return key;
            }
        }
        return Optional.empty();
    }

    /** ASCII letters in upper case; nothing else changes, so no other letter can become one of the key form. */
    private static String asciiUpper(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            out.append(c >= 'a' && c <= 'z' ? (char) (c - 32) : c);
        }
        return out.toString();
    }
}
