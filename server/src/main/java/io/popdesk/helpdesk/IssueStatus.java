package io.popdesk.helpdesk;

/** Issue status. A new issue is {@code RECEIVED}. */
public enum IssueStatus {
    RECEIVED,
    CONFIRMED,
    IN_PROGRESS,
    RESOLVED,
    CLOSED
}
