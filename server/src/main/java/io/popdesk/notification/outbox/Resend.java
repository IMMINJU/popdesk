package io.popdesk.notification.outbox;

import io.popdesk.ApiException;
import io.popdesk.Transactions;
import io.popdesk.notification.DeliveryAudit;
import io.popdesk.notification.outbox.OutboxRepository.Kept;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;

/**
 * Sending a {@code failed} row again (OPS-3, S4B-AC-14 to S4B-AC-20). It only puts the row back in the
 * queue, as a row that never failed, and wakes the channel's send loop: the loop sends it, with every
 * check it makes before a send, so an email whose recipient may no longer get it is dropped and not
 * sent (S4B-AC-15). Nothing is sent from here.
 *
 * <p>The checks come in the order of conventions "Order of checks": no such row is 404, then a channel
 * that is off is 400 {@code CHANNEL_DISABLED}, and the row stays as it is. A row that is in the queue
 * already, because it was just sent again, is left alone and is no error. Only a request that changed
 * the row leaves an audit entry.
 */
@Service
public class Resend {

    private final OutboxRepository outbox;
    private final Channels channels;
    private final DeliveryAudit audit;
    private final Wakeups wakeups;
    private final Transactions transactions;
    private final Clock clock;

    Resend(OutboxRepository outbox, Channels channels, DeliveryAudit audit, Wakeups wakeups,
            Transactions transactions, Clock clock) {
        this.outbox = outbox;
        this.channels = channels;
        this.audit = audit;
        this.wakeups = wakeups;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * @param actorAccountId the admin who asks
     * @param rowId the row's ID as the request gave it; a text that is no ID is a row that does not exist
     */
    public void resend(long actorAccountId, String rowId) {
        if (!rowId.matches("[0-9]{1,18}")) {
            throw ApiException.notFound();
        }
        long id = Long.parseLong(rowId);
        transactions.run(() -> {
            Kept row = outbox.findKept(id).orElseThrow(ApiException::notFound);
            if (!OutboxRepository.FAILED.equals(row.state())) {
                return;
            }
            if (!channels.enabled(row.channel())) {
                throw ApiException.channelDisabled();
            }
            if (outbox.updateQueuedAgain(id, clock.instant().truncatedTo(ChronoUnit.MICROS))) {
                audit.resent(actorAccountId, row.channel(), row.kind());
                wakeups.afterCommit(row.channel());
            } else if (outbox.findKept(id).isEmpty()) {
                // The cleanup took the row between the two statements.
                throw ApiException.notFound();
            }
        });
    }
}
