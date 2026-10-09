package io.popdesk.helpdesk.attachment;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * {@code popdesk_attachments_bytes}: the bytes of every stored attachment of the team, the sum that
 * {@code POPDESK_ATTACHMENT_QUOTA} limits (S0-AC-78, S0-AC-79). Read from the database on each scrape.
 */
@Component
class AttachmentMetrics {

    AttachmentMetrics(MeterRegistry registry, AttachmentRepository attachments) {
        Gauge.builder("popdesk.attachments", attachments, AttachmentRepository::storedBytes)
                .description("Bytes of all stored attachments")
                .baseUnit("bytes")
                .register(registry);
    }
}
