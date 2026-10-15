package io.popdesk.helpdesk.web.console;

/**
 * The kind of a link, as seen from the issue it is read on (S6C-AC-13). One link between A and B where
 * A is the duplicate reads {@code DUPLICATE_OF} on A and {@code DUPLICATED_BY} on B. Sending
 * {@code DUPLICATED_BY} makes the same link as sending {@code DUPLICATE_OF} from the other issue.
 */
public enum IssueLinkType {
    /** This issue is a duplicate of the other one. */
    DUPLICATE_OF,
    /** The other issue is a duplicate of this one. */
    DUPLICATED_BY,
    /** The two issues are related; no direction. */
    RELATED
}
