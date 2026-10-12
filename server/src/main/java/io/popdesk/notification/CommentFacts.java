package io.popdesk.notification;

/**
 * What the send loop asks about a comment just before it sends an email about it (MAIL-4): whether the
 * comment is still there, and whether it still mentions the recipient. Helpdesk owns the comments and
 * implements this.
 */
public interface CommentFacts {

    /** Whether the comment exists and is not deleted (S4-AC-55). */
    boolean present(long commentId);

    /** Whether the comment mentions the account now (S4-AC-56). */
    boolean mentions(long commentId, long accountId);
}
