package io.popdesk.notification;

import java.util.Optional;

/**
 * Invitation links for invitation emails. The database keeps only a hash of an invitation's token, so
 * the send loop asks for a new token when it first tries to send the email; the link made at the
 * invitation stops working. Helpdesk owns the invitations and implements this.
 */
public interface InvitationLinks {

    /**
     * Gives the account's open invitation a new token, in a transaction of its own, and returns the link
     * to the account's app (conventions "Invitation links"). Empty when the account is no longer
     * {@code INVITED} or has no open invitation.
     */
    Optional<String> renew(long accountId);
}
