package io.popdesk.notification.outbox;

import io.popdesk.notification.MailKind;
import io.popdesk.notification.MailQueue;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link MailQueue} on the {@code outbox} table. Sending is the send loop's work. */
@Service
class OutboxMailQueue implements MailQueue {

    private final OutboxRepository repository;
    private final Wakeups wakeups;
    private final Clock clock;
    private final boolean enabled;

    OutboxMailQueue(OutboxRepository repository, Wakeups wakeups, Clock clock, SmtpMail smtp) {
        this.repository = repository;
        this.wakeups = wakeups;
        this.clock = clock;
        this.enabled = smtp.enabled();
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
        wakeups.afterCommit(OutboxRepository.MAIL);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void queueAboutTask(MailKind kind, long recipientAccountId, UUID issueId, long taskId) {
        repository.insertMailAboutTask(kind.name(), recipientAccountId, issueId, taskId,
                clock.instant().truncatedTo(ChronoUnit.MICROS));
        wakeups.afterCommit(OutboxRepository.MAIL);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void queueAboutCompany(MailKind kind, long recipientAccountId, String companyCode) {
        repository.insertMailAboutCompany(kind.name(), recipientAccountId, companyCode,
                clock.instant().truncatedTo(ChronoUnit.MICROS));
        wakeups.afterCommit(OutboxRepository.MAIL);
    }
}
