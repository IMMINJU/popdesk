package io.popdesk.notification;

import java.util.Optional;

/**
 * Password reset links for reset emails. As with {@link InvitationLinks}, the database keeps only a
 * hash of the link's token, so the send loop asks for a new token when it first tries to send the
 * email. Helpdesk owns the links and implements this.
 */
public interface PasswordResetLinks {

    /**
     * Gives the account's reset link a new token, in a transaction of its own, and returns the link to
     * the account's app. Empty when the link is used or expired, or the account is no longer
     * {@code ACTIVE}.
     */
    Optional<String> renew(long accountId);
}
