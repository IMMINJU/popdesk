package io.popdesk.notification;

import java.util.UUID;

/**
 * Whether an account may see an issue now. The send loop asks just before it sends an email about an
 * issue, and drops the email when the answer is no (S0-AC-69, MAIL-4). Helpdesk implements this with
 * the same scope as its API.
 */
public interface IssueAccess {

    boolean canSee(long accountId, UUID issueId);
}
