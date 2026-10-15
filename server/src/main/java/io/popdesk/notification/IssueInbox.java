package io.popdesk.notification;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Tells one person that something happened on an issue (INBOX-1): an item in their inbox and, where the
 * event has one, an email. Helpdesk decides who is told and calls this once per person, inside the
 * transaction of the change, so the change, the item, and the queued email are kept or lost together
 * (S4-AC-37). The item and the email come from one call, so they cannot go to different people
 * (S4-AC-58).
 *
 * <p>The item holds IDs only. The issue's key and title and the name of who did it are read when the
 * inbox is read.
 */
public interface IssueInbox {

    /** Kinds of inbox items about an issue; stored in {@code notification.kind}, so the names never change. */
    enum Kind {
        /** Staff added a public comment, to the reporter. */
        STAFF_COMMENT,
        /** The reporter added a comment, to the assignee. */
        CUSTOMER_COMMENT,
        /** Staff resolved the issue, to the reporter. */
        ISSUE_RESOLVED,
        /** The issue was closed: by staff, to the reporter; by the reporter, to the assignee. */
        ISSUE_CLOSED,
        /** The issue was reopened: by staff, to the reporter; by the reporter, to the assignee. */
        ISSUE_REOPENED,
        /** Staff filed the issue on behalf of the reporter, to the reporter. */
        ISSUE_FILED_ON_BEHALF,
        /** A comment mentions the recipient. */
        MENTIONED,
        /** The recipient became the assignee. */
        ASSIGNED,
        /** The recipient became the assignee of a task of the issue (S6B-AC-33). */
        TASK_ASSIGNED
    }

    /**
     * Adds one item to the recipient's inbox and, with {@code mail}, queues that email to them. Joins
     * the caller's transaction; fails when there is none.
     *
     * @param issueId the issue it is about
     * @param commentId the comment it is about, if any
     * @param actorId the account that did it
     * @param mail the email that goes with the item; null for an event without one
     */
    void add(Kind kind, long recipientAccountId, UUID issueId, @Nullable Long commentId, long actorId,
            @Nullable MailKind mail);

    /**
     * Deletes every item about the comment, read or not, whoever has it (S4-AC-16): the comment was
     * deleted. Joins the caller's transaction.
     */
    void deleteOfComment(long commentId);

    /**
     * Deletes the account's unread {@link Kind#MENTIONED} items about the comment: an edit took the
     * account out of its mentions (S4-AC-56). Joins the caller's transaction.
     */
    void deleteUnreadMention(long commentId, long accountId);
}
