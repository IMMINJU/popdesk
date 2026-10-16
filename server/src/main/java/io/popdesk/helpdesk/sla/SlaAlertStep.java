package io.popdesk.helpdesk.sla;

import io.popdesk.Transactions;
import io.popdesk.helpdesk.sla.SlaAlerts.Alert;
import io.popdesk.helpdesk.sla.SlaIssueRepository.Due;
import io.popdesk.helpdesk.sla.SlaNotices.Goal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The alert step of {@code sla-recalculate} (S8B-AC-01 to S8B-AC-06, slice 8b design.md 0절 2 to 6): the goals of
 * live issues of the current policy version that came into warning or ran out as time went by are told once each.
 *
 * <p>The time is read when the step starts, to the microsecond, rather than taken from the run, which read it before
 * the earlier steps (0절 4). Batches of {@link #BATCH} issues, one transaction each, the earliest time to tell first,
 * until a batch is not full or {@link #BATCHES} ran. In a batch each issue's marks are written first, only while it
 * is still of the version read, and only then are its alerts told ({@link SlaNotices}): the marks, the inbox items,
 * the mail, and the webhook messages are kept or lost together, so a step is told once (S8B-AC-04). The policy is
 * not locked (0절 6); a row a write holds is skipped and taken at a later run.
 */
@Component
class SlaAlertStep {

    /** Issues one transaction looks at most: the lock of each is held until its batch commits. */
    static final int BATCH = 100;

    /** Batches one run takes at most. */
    static final int BATCHES = 10;

    private final SlaPolicyRepository policyRows;
    private final SlaIssueRepository issues;
    private final SlaNotices notices;
    private final Transactions transactions;
    private final Clock clock;

    SlaAlertStep(SlaPolicyRepository policyRows, SlaIssueRepository issues, SlaNotices notices,
            Transactions transactions, Clock clock) {
        this.policyRows = policyRows;
        this.issues = issues;
        this.notices = notices;
        this.transactions = transactions;
        this.clock = clock;
    }

    void run() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        for (int batch = 0; batch < BATCHES; batch++) {
            int looked = transactions.run(() -> batch(now));
            if (looked < BATCH) {
                break;
            }
        }
    }

    /** One batch; gives how many issues it locked. */
    private int batch(Instant now) {
        int current = policyRows.current();
        List<Due> due = issues.lockDue(current, now, BATCH);
        List<SlaNotices.Alert> told = new ArrayList<>();
        for (Due issue : due) {
            IssueClocks clocks = issue.clocks();
            Alert firstResponse = SlaAlerts.alert(clocks.firstResponse(), clocks.firstResponseAlerted(), now);
            Alert resolution = SlaAlerts.alert(clocks.resolution(), clocks.resolutionAlerted(), now);
            if (firstResponse.tell() == null && resolution.tell() == null
                    || !issues.updateMarks(issue.id(), current, clocks.marked(firstResponse.mark(), resolution.mark()))) {
                continue;
            }
            if (firstResponse.tell() != null) {
                told.add(new SlaNotices.Alert(issue.id(), issue.companyId(), issue.assigneeId(), Goal.FIRST_RESPONSE,
                        firstResponse.tell()));
            }
            if (resolution.tell() != null) {
                told.add(new SlaNotices.Alert(issue.id(), issue.companyId(), issue.assigneeId(), Goal.RESOLUTION,
                        resolution.tell()));
            }
        }
        if (!told.isEmpty()) {
            notices.tell(told);
        }
        return due.size();
    }
}
