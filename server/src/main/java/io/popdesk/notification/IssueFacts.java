package io.popdesk.notification;

import java.util.Optional;
import java.util.UUID;

/**
 * What an email or a webhook message says about an issue, read when it is sent (MAIL-5): the key, the
 * title, and the company code. Helpdesk owns the issues and implements this.
 */
public interface IssueFacts {

    /**
     * @param key the issue key, {@code CODE-n}
     * @param title the title as the customer wrote it, line breaks included
     * @param companyCode the code of the issue's company
     */
    record Issue(String key, String title, String companyCode) {}

    /** The issue, or empty when there is none. */
    Optional<Issue> find(UUID issueId);
}
