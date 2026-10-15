package io.popdesk.notification;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Queues a message to the team's webhook ({@code NOTIFY_WEBHOOK_URL}) in the outbox. Helpdesk calls
 * this inside the transaction of the change that causes the message, so the change and the queue row
 * are kept or lost together (S0-AC-66). The row holds only what the message is about; the text is made
 * when it is sent.
 */
public interface WebhookQueue {

    /** Whether the webhook is sent: {@code NOTIFY_WEBHOOK_URL} is set and this is no sandbox. */
    boolean enabled();

    /**
     * Queues one message in the caller's transaction, and fails when there is none. Does nothing when
     * no webhook is configured: then no row is written at all (S0-AC-77).
     *
     * @param kind what the message is about
     * @param issueId the issue it is about
     * @param commentId the comment it is about, if any
     */
    void queue(WebhookKind kind, UUID issueId, @Nullable Long commentId);

    /**
     * Deletes, in the caller's transaction, every webhook row about the issue, whatever its state
     * (S7A-AC-10), as {@link MailQueue#dropAbout} does for emails.
     */
    void dropAbout(UUID issueId);
}
