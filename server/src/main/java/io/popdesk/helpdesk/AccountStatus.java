package io.popdesk.helpdesk;

/** Account status. {@code INVITED}: invited, not accepted yet; cannot sign in (INVITE-5). */
public enum AccountStatus {
    INVITED,
    ACTIVE,
    SUSPENDED
}
