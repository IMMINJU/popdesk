package io.popdesk.notification.ops;

import io.popdesk.notification.IssueInbox;
import io.popdesk.notification.MailKind;
import io.popdesk.notification.MailQueue;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link IssueInbox} on the table {@code notification}, with the email through the {@link MailQueue}. */
@Service
class IssueInboxService implements IssueInbox {

    private final OpsRepository ops;
    private final MailQueue mail;
    private final Clock clock;

    IssueInboxService(OpsRepository ops, MailQueue mail, Clock clock) {
        this.ops = ops;
        this.mail = mail;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void add(Kind kind, long recipientAccountId, UUID issueId, @Nullable Long commentId, long actorId,
            @Nullable MailKind mailKind) {
        ops.insertInbox(recipientAccountId, kind.name(), issueId, commentId, actorId,
                clock.instant().truncatedTo(ChronoUnit.MICROS));
        if (mailKind != null) {
            mail.queue(mailKind, recipientAccountId, issueId, commentId);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void addAboutTask(long recipientAccountId, UUID issueId, long taskId, long actorId) {
        ops.insertInboxAboutTask(recipientAccountId, Kind.TASK_ASSIGNED.name(), issueId, taskId, actorId,
                clock.instant().truncatedTo(ChronoUnit.MICROS));
        mail.queueAboutTask(MailKind.TASK_ASSIGNED, recipientAccountId, issueId, taskId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteOfComment(long commentId) {
        ops.deleteInboxOfComment(commentId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteOfTask(long taskId) {
        ops.deleteInboxOfTask(taskId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteUnreadMention(long commentId, long accountId) {
        ops.deleteUnreadInbox(commentId, accountId, Kind.MENTIONED.name());
    }
}
