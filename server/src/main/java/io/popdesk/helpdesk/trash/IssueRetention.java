package io.popdesk.helpdesk.trash;

import io.popdesk.Transactions;
import io.popdesk.helpdesk.issue.IssueRepository;
import io.popdesk.helpdesk.trash.RetentionSchedule.Decision;
import io.popdesk.helpdesk.trash.RetentionSchedule.State;
import io.popdesk.helpdesk.trash.RetentionSchedule.Step;
import io.popdesk.notification.AdminInbox;
import io.popdesk.notification.CleanupJob;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Automatic issue cleanup, {@code issue-retention}, every hour (PII-6, S10B-AC-19, S10B-AC-20, S10B-AC-23): each run
 * reads what the last run saw from the state row and lets {@link RetentionSchedule} decide. On the first day it
 * moves nothing and tells every admin how many issues it would move ({@code ISSUE_RETENTION_ARMED}); after the wait it
 * moves the due issues to the trash at most once a day, a day after the last run that moved one, one issue per
 * transaction ({@link TrashService#moveDue}). The count recorded is the issues moved; a run that moves nothing, the
 * cleanup off among them, records 0.
 *
 * <p>The scheduler runs it only while this instance holds the single-instance lock, on one thread, so two runs never
 * meet; the state row is locked all the same while it is written.
 */
@Component
class IssueRetention implements CleanupJob {

    static final String NAME = "issue-retention";

    private static final Logger log = LoggerFactory.getLogger(IssueRetention.class);

    private final IssueRetentionSettings settings;
    private final RetentionStateRepository state;
    private final IssueRepository issues;
    private final TrashService trash;
    private final AdminInbox inbox;
    private final Transactions transactions;

    IssueRetention(IssueRetentionSettings settings, RetentionStateRepository state, IssueRepository issues,
            TrashService trash, AdminInbox inbox, Transactions transactions) {
        this.settings = settings;
        this.state = state;
        this.issues = issues;
        this.trash = trash;
        this.inbox = inbox;
        this.transactions = transactions;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Duration interval() {
        return Duration.ofHours(1);
    }

    @Override
    public long run(Instant now) {
        Duration period = settings.period().orElse(null);
        State[] last = new State[1];
        Decision decision = transactions.run(() -> {
            last[0] = state.lock();
            Decision decided = RetentionSchedule.decide(last[0], period, now);
            if (decided.step() == Step.MOVE) {
                // The last move is written once the moves are done, so a run that fails is tried again.
                return decided;
            }
            state.update(decided.next());
            if (decided.step() == Step.ARM) {
                long due = issues.countDueForRetention(now.minus(period));
                inbox.issueRetentionArmed(due, period.toDays());
                log.info("Automatic issue cleanup was turned on or shortened: it moves {} issues to the trash in 24 "
                        + "hours, and nothing before.", due);
            }
            return decided;
        });
        if (decision.step() != Step.MOVE) {
            return 0;
        }
        long moved = trash.moveDue(now, period);
        transactions.run(() -> {
            state.lock();
            state.update(RetentionSchedule.afterMoves(last[0], decision, moved));
        });
        if (moved > 0) {
            log.info("Automatic issue cleanup moved {} issues to the trash.", moved);
        }
        return moved;
    }
}
