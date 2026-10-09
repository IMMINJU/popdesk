package io.popdesk.notification.outbox;

import io.popdesk.PageRequest;
import io.popdesk.Paged;
import io.popdesk.notification.outbox.OutboxRepository.Count;
import io.popdesk.notification.outbox.OutboxRepository.FailedRow;
import java.time.Instant;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the system status shows of each channel (S0-AC-81, OPS-3 in part): whether it is configured,
 * its last success, and its queued and failed rows. Never the SMTP password or the webhook URL.
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

    /** {@code failed} rows of both channels, the latest failure first. */
    @Transactional(readOnly = true)
    public Paged<FailedRow> failed(int page, int size) {
        PageRequest request = PageRequest.of(page, size);
        return request.of(outbox.listFailed(request.offset(), request.size()), outbox.countFailed());
    }

    private Channel channel(String channel, String key) {
        String setting = environment.getProperty(key);
        List<Count> counts = outbox.counts();
        return new Channel(setting != null && !setting.isBlank(), outbox.lastSuccess(channel).orElse(null),
                rows(counts, channel, OutboxRepository.QUEUED), rows(counts, channel, OutboxRepository.FAILED));
    }

    private static long rows(List<Count> counts, String channel, String state) {
        return counts.stream().filter(c -> c.channel().equals(channel) && c.state().equals(state))
                .mapToLong(Count::rows).sum();
    }
}
