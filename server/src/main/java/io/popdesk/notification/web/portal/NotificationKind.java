package io.popdesk.notification.web.portal;

/**
 * Kinds of inbox items a customer gets (S4-AC-46). The names are those of the console's kinds; the
 * console has more.
 */
public enum NotificationKind {
    /** The support team added a public comment (S4-AC-27). */
    STAFF_COMMENT,
    /** The support team resolved the issue (S4-AC-29). */
    ISSUE_RESOLVED,
    /** The support team closed the issue (S4-AC-29). */
    ISSUE_CLOSED,
    /** The support team reopened the issue (S4-AC-29). */
    ISSUE_REOPENED,
    /** The support team filed the issue on behalf of the caller (S4-AC-32). */
    ISSUE_FILED_ON_BEHALF,
    /** A public comment mentions the caller (S4-AC-33). */
    MENTIONED
}
