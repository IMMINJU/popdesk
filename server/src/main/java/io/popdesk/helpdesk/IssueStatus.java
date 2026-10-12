package io.popdesk.helpdesk;

import java.util.List;

/** Issue status. A new issue is {@code RECEIVED}. */
public enum IssueStatus {
    RECEIVED,
    CONFIRMED,
    IN_PROGRESS,
    RESOLVED,
    CLOSED;

    /**
     * The statuses staff may set from this one (STATUS-1). This is the one place the transition table
     * is written: the status change checks against it and the console draws its buttons from it. The
     * reporter closing an issue (STATUS-2) and reopening (STATUS-4) are not staff transitions and are
     * not in it.
     */
    public List<IssueStatus> next() {
        return switch (this) {
            case RECEIVED -> List.of(CONFIRMED, IN_PROGRESS, CLOSED);
            case CONFIRMED -> List.of(IN_PROGRESS, CLOSED);
            case IN_PROGRESS -> List.of(RESOLVED);
            case RESOLVED -> List.of(IN_PROGRESS, CLOSED);
            case CLOSED -> List.of();
        };
    }
}
