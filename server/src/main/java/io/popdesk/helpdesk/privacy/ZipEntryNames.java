package io.popdesk.helpdesk.privacy;

import java.util.HashSet;
import java.util.Set;

/**
 * The paths of the files in a company export's ZIP (S10B-AC-11, acceptance.md 5절 6): an attachment is
 * {@code issues/{key}/files/{attachment ID}-{file name}}, with the file name made safe, so no path holds
 * {@code ..}, a backslash, a control character, or starts with {@code /}, whatever name was uploaded. Pure: no
 * clock, no I/O. One instance gives the paths of one ZIP and never gives the same path twice.
 */
final class ZipEntryNames {

    private final Set<String> given = new HashSet<>();

    /**
     * The file name as it goes into a path: {@code /}, {@code \}, and control characters (ISO control, U+0000 to
     * U+001F and U+007F to U+009F) become {@code _}, every leading dot becomes {@code _}, and a dot followed by
     * another dot becomes {@code _}, so the name holds no {@code ..}. Other characters stay as they are.
     *
     * @param name the uploaded file name, any text; null or empty is taken as an empty name
     * @return a name of at least one character that holds none of the characters above; {@code _} for an empty name
     */
    static String safe(String name) {
        if (name == null || name.isEmpty()) {
            return "_";
        }
        StringBuilder out = new StringBuilder(name.length());
        boolean leading = true;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean dot = c == '.';
            if (c == '/' || c == '\\' || Character.isISOControl(c)
                    || dot && (leading || i + 1 < name.length() && name.charAt(i + 1) == '.')) {
                out.append('_');
            } else {
                out.append(c);
            }
            leading = leading && dot;
        }
        return out.toString();
    }

    /**
     * The path of an attachment's file: {@code issues/{key}/files/{attachmentId}-{safe file name}}, both the key and
     * the name made {@link #safe}. Should this instance have given that path already, a number is put after it
     * ({@code ~2}, {@code ~3}, ...) until the path is new.
     *
     * @param issueKey the issue's key, such as {@code ACME-12}
     * @param attachmentId the attachment's ID, a UUID as text
     * @param fileName the uploaded file name, any text
     * @return a path this instance has not given before
     */
    String file(String issueKey, String attachmentId, String fileName) {
        String path = issue(issueKey) + "files/" + safe(attachmentId) + "-" + safe(fileName);
        String unique = path;
        for (int n = 2; !given.add(unique); n++) {
            unique = path + "~" + n;
        }
        return unique;
    }

    /**
     * The folder of an issue, {@code issues/{key}/}, with the key made {@link #safe}.
     *
     * @param issueKey the issue's key, any text
     * @return the folder's path, ending in {@code /}
     */
    static String issue(String issueKey) {
        return "issues/" + safe(issueKey) + "/";
    }
}
