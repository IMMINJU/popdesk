package io.popdesk.notification.outbox;

import io.popdesk.PageRequest;
import io.popdesk.Paged;
import io.popdesk.notification.Sandbox;
import io.popdesk.notification.outbox.OutboxRepository.Count;
import io.popdesk.notification.outbox.OutboxRepository.FailedRow;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the system status shows of each channel (S0-AC-81, S1B-AC-33, OPS-3 in part): whether it is on
 * ({@link Channels}), its last success, and its queued and failed rows; and of the sandbox, whether
 * this is one and whether it has an address (S4B-AC-08). Never the SMTP password, the webhook URL, the
 * address or token of the audit log's outside copy, or the sandbox address.
 */
@Service
public class DeliveryStatus {

    /** One channel. */
    public record Channel(boolean enabled, Instant lastSuccessAt, long queued, long failed) {}

    private final OutboxRepository outbox;
    private final Channels channels;
    private final Sandbox sandbox;

    DeliveryStatus(OutboxRepository outbox, Channels channels, Sandbox sandbox) {
        this.outbox = outbox;
        this.channels = channels;
        this.sandbox = sandbox;
    }

    @Transactional(readOnly = true)
    public Channel mail() {
        return channel(OutboxRepository.MAIL);
    }

    @Transactional(readOnly = true)
    public Channel webhook() {
        return channel(OutboxRepository.WEBHOOK);
    }

    /** The outside copy of the audit log; off in a sandbox although its address is set. */
    @Transactional(readOnly = true)
    public Channel auditForward() {
        return channel(OutboxRepository.AUDIT_FORWARD);
    }

    /** Whether this is a sandbox ({@code POPDESK_SANDBOX=true}). */
    public boolean sandbox() {
        return sandbox.on();
    }

    /** Whether a sandbox has an address its email goes to. The address itself is never given out. */
    public boolean sandboxMailToSet() {
        return sandbox.mailTo().isPresent();
    }

    /** {@code failed} rows of every channel, the latest failure first. */
    @Transactional(readOnly = true)
    public Paged<FailedRow> failed(int page, int size) {
        PageRequest request = PageRequest.of(page, size);
        return request.of(outbox.listFailed(request.offset(), request.size()), outbox.countFailed());
    }

    private Channel channel(String channel) {
        List<Count> counts = outbox.counts();
        return new Channel(channels.enabled(channel), outbox.lastSuccess(channel).orElse(null),
                rows(counts, channel, OutboxRepository.QUEUED), rows(counts, channel, OutboxRepository.FAILED));
    }

    private static long rows(List<Count> counts, String channel, String state) {
        return counts.stream().filter(c -> c.channel().equals(channel) && c.state().equals(state))
                .mapToLong(Count::rows).sum();
    }
}
