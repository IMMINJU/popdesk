package io.popdesk.helpdesk;

/**
 * The kind of a link between two issues as seen from one of them (LINK-1, slice 6c acceptance section 5,
 * 2). The table stores {@code DUPLICATE_OF} and {@code RELATED}; {@code DUPLICATED_BY} is a
 * {@code DUPLICATE_OF} link seen from its original. The names are part of the API and of the stored
 * history values.
 */
public enum LinkType {
    /** This issue is a duplicate of the other one. */
    DUPLICATE_OF,
    /** The other issue is a duplicate of this one. */
    DUPLICATED_BY,
    /** The two issues are related; no direction. */
    RELATED;

    /**
     * The same link seen from the other issue.
     *
     * @return {@code DUPLICATED_BY} for {@code DUPLICATE_OF} and the reverse; {@code RELATED} for itself
     */
    public LinkType reversed() {
        return switch (this) {
            case DUPLICATE_OF -> DUPLICATED_BY;
            case DUPLICATED_BY -> DUPLICATE_OF;
            case RELATED -> RELATED;
        };
    }
}
