package io.popdesk.notification.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.popdesk.notification.outbox.OutboxRepository.Count;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Outbox metrics (S0-AC-78, architecture.md section 6), label {@code channel} = {@code mail} or
 * {@code webhook}, both always there even when a channel is off: {@code popdesk_outbox_queued} and
 * {@code popdesk_outbox_failed} (rows now), {@code popdesk_outbox_sent_total} (rows sent). The two
 * gauges hold the counts of the last {@link #refresh()}. No address or URL is ever a label.
 */
@Component
public class OutboxMetrics {

    private static final List<String> CHANNELS = List.of(OutboxRepository.MAIL, OutboxRepository.WEBHOOK);

    private final OutboxRepository outbox;
    private final Map<String, AtomicLong> queued;
    private final Map<String, AtomicLong> failed;
    private final Map<String, Counter> sent;

    OutboxMetrics(OutboxRepository outbox, MeterRegistry registry) {
        this.outbox = outbox;
        this.queued = gauges(registry, "popdesk.outbox.queued", "Outbox rows waiting to be sent");
        this.failed = gauges(registry, "popdesk.outbox.failed", "Outbox rows that stopped retrying");
        this.sent = Map.of(
                OutboxRepository.MAIL, counter(registry, OutboxRepository.MAIL),
                OutboxRepository.WEBHOOK, counter(registry, OutboxRepository.WEBHOOK));
    }

    private static Map<String, AtomicLong> gauges(MeterRegistry registry, String name, String description) {
        Map<String, AtomicLong> out = Map.of(OutboxRepository.MAIL, new AtomicLong(),
                OutboxRepository.WEBHOOK, new AtomicLong());
        for (String channel : CHANNELS) {
            Gauge.builder(name, out.get(channel), AtomicLong::get)
                    .description(description)
                    .tag("channel", label(channel))
                    .register(registry);
        }
        return out;
    }

    private static Counter counter(MeterRegistry registry, String channel) {
        return Counter.builder("popdesk.outbox.sent")
                .description("Outbox rows sent")
                .tag("channel", label(channel))
                .register(registry);
    }

    private static String label(String channel) {
        return channel.toLowerCase(Locale.ROOT);
    }

    void sent(String channel) {
        sent.get(channel).increment();
    }

    /** Reads the row counts again. */
    public void refresh() {
        List<Count> counts = outbox.counts();
        for (String channel : CHANNELS) {
            queued.get(channel).set(sum(counts, channel, OutboxRepository.QUEUED));
            failed.get(channel).set(sum(counts, channel, OutboxRepository.FAILED));
        }
    }

    private static long sum(List<Count> counts, String channel, String state) {
        return counts.stream().filter(c -> c.channel().equals(channel) && c.state().equals(state))
                .mapToLong(Count::rows).sum();
    }
}
