package io.popdesk.notification.outbox;

import io.popdesk.notification.MailKind;
import io.popdesk.notification.MailQueue;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link MailQueue} on the {@code outbox} table. Sending is the send loop's work. */
@Service
class OutboxMailQueue implements MailQueue {

    private final OutboxRepository repository;
    private final Clock clock;
    private final boolean enabled;

    OutboxMailQueue(OutboxRepository repository, Clock clock, Environment environment) {
        this.repository = repository;
        this.clock = clock;
        String host = environment.getProperty("SMTP_HOST");
        this.enabled = host != null && !host.isBlank();
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void queue(MailKind kind, long recipientAccountId, @Nullable UUID issueId, @Nullable Long commentId) {
        repository.insert(OutboxRepository.MAIL, kind.name(), recipientAccountId, issueId, commentId,
                clock.instant().truncatedTo(ChronoUnit.MICROS));
    }
}
