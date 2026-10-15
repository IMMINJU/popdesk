package io.popdesk.helpdesk.issue;

import io.popdesk.Transactions;
import io.popdesk.helpdesk.IssueStatus;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.issue.IssueHistoryRepository.Field;
import io.popdesk.helpdesk.issue.IssueRepository.IssueRow;
import io.popdesk.notification.CleanupJob;
import io.popdesk.notification.WebhookKind;
import io.popdesk.notification.WebhookQueue;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Closes the live {@code RESOLVED} issues 7 days after their time to close from, {@code issue-auto-close},
 * every 10 minutes (STATUS-5, S7A-AC-40). Popdesk itself closes them ({@link Viewer#system()}, actor 0),
 * with what staff closing an issue leaves: a history entry, the webhook message, the reporter's inbox
 * item and email, and the items of the assignees of linked issues (S7A-AC-43).
 *
 * <p>One batch of {@link #BATCH} issues per transaction, until none is left (slice 7a design section 4):
 * the batch is locked in ID order, skipping a row another transaction holds, and closed by a conditional
 * UPDATE on the same conditions, so an issue reopened or deleted meanwhile stays as it is (S7A-AC-42).
 * A skipped issue is closed by a later run. The count recorded is the issues closed.
 */
@Component
class IssueAutoClose implements CleanupJob {

    static final String NAME = "issue-auto-close";

    /** How long a {@code RESOLVED} issue waits before popdesk closes it. */
    static final Duration AFTER = Duration.ofDays(7);

    /** Issues one transaction closes at most. */
    static final int BATCH = 100;

    private final IssueRepository issues;
    private final IssueHistoryRepository history;
    private final WebhookQueue webhooks;
    private final IssueNotifications notifications;
    private final Transactions transactions;

    IssueAutoClose(IssueRepository issues, IssueHistoryRepository history, WebhookQueue webhooks,
            IssueNotifications notifications, Transactions transactions) {
        this.issues = issues;
        this.history = history;
        this.webhooks = webhooks;
        this.notifications = notifications;
        this.transactions = transactions;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Duration interval() {
        return Duration.ofMinutes(10);
    }

    @Override
    public long run(Instant now) {
        Instant until = now.minus(AFTER);
        long closed = 0;
        int[] locked = new int[1];
        do {
            closed += transactions.run(() -> {
                List<UUID> batch = issues.lockDueForClosing(until, BATCH);
                locked[0] = batch.size();
                List<UUID> done = issues.updateClosedAutomatically(batch, until, now);
                for (UUID id : batch.stream().filter(done::contains).toList()) {
                    closed(id, now);
                }
                return done.size();
            });
        } while (locked[0] == BATCH);
        return closed;
    }

    /** What closing one issue leaves, as {@code IssueService} leaves it for staff, by popdesk itself. */
    private void closed(UUID id, Instant now) {
        Viewer system = Viewer.system();
        history.insertChanged(id, Field.STATUS, IssueStatus.RESOLVED.name(), IssueStatus.CLOSED.name(),
                system.accountId(), now);
        webhooks.queue(WebhookKind.ISSUE_CLOSED, id, null);
        IssueRow issue = issues.findById(system, id).orElseThrow();
        notifications.statusChanged(system, issue, IssueStatus.CLOSED);
        notifications.linkedStatusChanged(system, issue, IssueStatus.CLOSED);
    }
}
