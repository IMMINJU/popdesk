package io.popdesk.notification.ops;

import io.popdesk.ApiException;
import io.popdesk.PageRequest;
import io.popdesk.Paged;
import io.popdesk.notification.ops.OpsRepository.InboxRow;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The caller's inbox (S0-AC-80, INBOX-3 in part): only their own items. */
@Service
public class Inbox {

    private final OpsRepository ops;
    private final Clock clock;

    Inbox(OpsRepository ops, Clock clock) {
        this.ops = ops;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Paged<InboxRow> list(long accountId, int page, int size) {
        PageRequest request = PageRequest.of(page, size);
        return request.of(ops.listInbox(accountId, request.offset(), request.size()), ops.countInbox(accountId));
    }

    @Transactional(readOnly = true)
    public long unread(long accountId) {
        return ops.countUnread(accountId);
    }

    /** Marks the item read; 404 when it is not the caller's. Marking it again keeps the first time. */
    @Transactional
    public void markRead(long accountId, long id) {
        if (!ops.updateRead(id, accountId, clock.instant().truncatedTo(ChronoUnit.MICROS))) {
            throw ApiException.notFound();
        }
    }
}
