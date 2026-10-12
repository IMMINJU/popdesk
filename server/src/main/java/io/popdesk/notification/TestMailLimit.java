package io.popdesk.notification;

/**
 * The limit on test emails (COM-8, S4B-AC-26): 5 in 60 seconds for one admin. Helpdesk counts the
 * limits of an account and implements this.
 */
public interface TestMailLimit {

    /** Counts one test email of the account; over the limit, throws 429 with {@code Retry-After}. */
    void count(long accountId);
}
