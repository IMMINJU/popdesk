package io.popdesk.notification;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The record of the email popdesk sent to customers about an issue (MAIL-7): its {@code Message-ID} and the issue,
 * so that a reply finds the issue (MAILIN-2). Each {@code Message-ID} is kept without its angle brackets; ask
 * without them too.
 */
public interface SentMailLookup {

    /**
     * The issues of those of these emails that are recorded, in one query. Which of them a reply continues (the last
     * in its headers that the sender may see, S12-AC-15) is the caller's to decide.
     *
     * @param messageIds {@code Message-ID}s without the angle brackets; may be empty
     * @return each recorded {@code Message-ID} with its issue; empty when none is
     */
    Map<String, UUID> issuesOf(Collection<String> messageIds);

    /**
     * Deletes the records about the issue, in the caller's transaction: it is purged (design.md 0절 12).
     *
     * @return how many there were
     */
    int deleteAbout(UUID issueId);

    /**
     * Deletes the records of email sent before {@code before}, in the caller's transaction (the cleanup).
     *
     * @return how many there were
     */
    int deleteSentBefore(Instant before);
}
