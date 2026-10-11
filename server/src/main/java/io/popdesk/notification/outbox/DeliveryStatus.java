package io.popdesk.notification.outbox;

import io.popdesk.PageRequest;
import io.popdesk.Paged;
import io.popdesk.notification.AuditCopyRows;
import io.popdesk.notification.outbox.OutboxRepository.Count;
import io.popdesk.notification.outbox.OutboxRepository.FailedRow;
import java.time.Instant;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the system status shows of each channel (S0-AC-81, S1B-AC-33, OPS-3 in part): whether it is
 * configured, its last success, and its queued and failed rows. Never the SMTP password, the webhook
 * URL, or the address or token of the audit log's outside copy.
 */
@Service
public class DeliveryStatus {

    /** One channel. */
    public record Channel(boolean enabled, Instant lastSuccessAt, long queued, long failed) {}

    private final OutboxRepository outbox;
    private final Environment environment;

    DeliveryStatus(OutboxRepository outbox, Environment environment) {
        this.outbox = outbox;
        this.environment = environment;
    }

    @Transactional(readOnly = true)
    public Channel mail() {
        return channel(OutboxRepository.MAIL, "SMTP_HOST");
    }

    @Transactional(readOnly = true)
    public Channel webhook() {
        return channel(OutboxRepository.WEBHOOK, "NOTIFY_WEBHOOK_URL");
    }

    /** The outside copy of the audit log; off in a sandbox although its address is set. */
    @Transactional(readOnly = true)
    public Channel auditForward() {
        return channel(OutboxRepository.AUDIT_FORWARD, AuditCopyRows.enabled(environment::getProperty));
    }

    /** {@code failed} rows of every channel, the latest failure first. */
    @Transactional(readOnly = true)
    public Paged<FailedRow> failed(int page, int size) {
        PageRequest request = PageRequest.of(page, size);
        return request.of(outbox.listFailed(request.offset(), request.size()), outbox.countFailed());
    }

    private Channel channel(String channel, String key) {
        String setting = environment.getProperty(key);
        return channel(channel, setting != null && !setting.isBlank());
    }

    private Channel channel(String channel, boolean enabled) {
        List<Count> counts = outbox.counts();
        return new Channel(enabled, outbox.lastSuccess(channel).orElse(null),
                rows(counts, channel, OutboxRepository.QUEUED), rows(counts, channel, OutboxRepository.FAILED));
    }

    private static long rows(List<Count> counts, String channel, String state) {
        return counts.stream().filter(c -> c.channel().equals(channel) && c.state().equals(state))
                .mapToLong(Count::rows).sum();
    }
}
