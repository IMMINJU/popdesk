package io.popdesk.notification.outbox;

import io.popdesk.notification.outbox.OutboxRepository.OutboxRow;

/** Sends the rows of one channel. Called by that channel's send loop only, outside any transaction. */
interface Sender {

    /** {@link OutboxRepository#MAIL} or {@link OutboxRepository#WEBHOOK}. */
    String channel();

    /** Checks the row against the database now and sends it, or not. Never throws for a failed send. */
    Attempt attempt(OutboxRow row);

    /** The row is gone or given up; forget anything kept for its next attempt. */
    default void forget(long rowId) {}
}
