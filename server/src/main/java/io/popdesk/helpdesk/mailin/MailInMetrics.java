package io.popdesk.helpdesk.mailin;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.popdesk.notification.MailInState.FailureKind;
import org.springframework.stereotype.Component;

/**
 * The metrics of receiving email (S12-AC-35): {@code popdesk.mailin.messages} by {@code outcome},
 * {@code popdesk.mailin.unauthenticated}, and {@code popdesk.mailin.round.failures} by {@code kind}.
 */
@Component
class MailInMetrics {

    private final MeterRegistry registry;
    private final Counter unauthenticated;

    MailInMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.unauthenticated = Counter.builder("popdesk.mailin.unauthenticated")
                .description("Received emails taken without a DMARC pass (S12-AC-12)").register(registry);
    }

    /** One email handled with this outcome ({@code ISSUE}, {@code COMMENT}, {@code NOTICE}, ...). */
    void message(String outcome) {
        Counter.builder("popdesk.mailin.messages").description("Received emails by outcome")
                .tag("outcome", outcome).register(registry).increment();
    }

    /** One email taken whose {@code Authentication-Results} hold no DMARC pass. */
    void unauthenticated() {
        unauthenticated.increment();
    }

    /** One failed round. */
    void roundFailed(FailureKind kind) {
        Counter.builder("popdesk.mailin.round.failures").description("Mail-in rounds that failed, by kind")
                .tag("kind", kind.name()).register(registry).increment();
    }
}
