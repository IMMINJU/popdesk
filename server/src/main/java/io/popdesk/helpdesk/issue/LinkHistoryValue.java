package io.popdesk.helpdesk.issue;

import io.popdesk.helpdesk.LinkType;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The value a {@code LINK} history entry stores (LINK-7, S6C-AC-20, slice 6c design section 0, 3): the
 * type of the link as seen from the issue whose history it is, and the other issue's ID, as
 * {@code {type}:{uuid}}, for example {@code DUPLICATE_OF:0b6f…}. Never the other issue's key or title,
 * which are read when the history is. No I/O.
 */
public final class LinkHistoryValue {

    /** A stored value read back: the type seen from the issue of the entry, and the other issue. */
    public record Value(LinkType type, UUID otherIssueId) {}

    /** A uuid in its canonical form of 36 characters, lower or upper case. */
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private LinkHistoryValue() {}

    /**
     * The value to store on the issue whose history it is.
     *
     * @param type any of the three, as seen from that issue
     * @param otherIssueId the other issue of the link, not null
     * @return {@code {type}:{uuid}} with the uuid in lower case
     */
    public static String of(LinkType type, UUID otherIssueId) {
        return type.name() + ":" + otherIssueId;
    }

    /**
     * The value to store on the other issue of the same link: the type reversed ({@link LinkType#reversed})
     * and this issue as its other issue.
     *
     * @param type the type as seen from this issue
     * @param thisIssueId this issue, not null
     */
    public static String ofOtherSide(LinkType type, UUID thisIssueId) {
        return of(type.reversed(), thisIssueId);
    }

    /**
     * Reads a stored value.
     *
     * @param text any text, or null
     * @return the type and the other issue; empty for null and for text that is not a type name, a colon,
     *     and a uuid in its canonical form, exactly
     */
    public static Optional<Value> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        int colon = text.indexOf(':');
        if (colon < 0) {
            return Optional.empty();
        }
        String name = text.substring(0, colon);
        String id = text.substring(colon + 1);
        for (LinkType type : LinkType.values()) {
            if (type.name().equals(name) && UUID_TEXT.matcher(id).matches()) {
                return Optional.of(new Value(type, UUID.fromString(id)));
            }
        }
        return Optional.empty();
    }
}
