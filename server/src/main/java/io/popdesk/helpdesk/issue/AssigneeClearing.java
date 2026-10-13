package io.popdesk.helpdesk.issue;

import io.popdesk.helpdesk.OpenAssignments;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.issue.IssueHistoryRepository.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The open issues a person is the assignee of (ACCT-7): how many there are, and clearing them when an
 * admin takes an agent off a company or suspends an account. "Open" is every status but
 * {@code CLOSED}; a closed issue keeps its assignee as the record of who handled it (S3-AC-70). The
 * count and the clearing use one condition, so the number an admin read is the number cleared unless
 * an issue changed in between.
 */
@Service
public class AssigneeClearing implements OpenAssignments {

    private final IssueRepository issues;
    private final IssueHistoryRepository history;
    private final Clock clock;

    AssigneeClearing(IssueRepository issues, IssueHistoryRepository history, Clock clock) {
        this.issues = issues;
        this.history = history;
        this.clock = clock;
    }

    /**
     * How many open issues the account is the assignee of, among those the viewer, an admin, sees; with
     * {@code companyId}, in that company only (S3-AC-68).
     */
    @Override
    public long count(Viewer viewer, long accountId, Long companyId) {
        return issues.countOpenAssignments(viewer, accountId, companyId);
    }

    /**
     * Clears the assignee of those issues in the caller's transaction, which is the one that takes the
     * agent off the company or suspends the account, after its checks: a refused request clears nothing
     * (S3-AC-71). With {@code companyId}, only the issues of that company (S3-AC-69). Each cleared issue
     * gets a history entry by {@code actorId}, the admin; no webhook message is queued (S3-AC-70).
     *
     * <p>The issue rows are not locked one by one: the UPDATE carries the condition, and an issue whose
     * assignee or status another request changed first is left to that request.
     *
     * @return how many issues were cleared
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int clear(long accountId, Long companyId, long actorId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<UUID> cleared = issues.updateAssigneesCleared(accountId, companyId, now);
        for (UUID issueId : cleared) {
            history.insertChanged(issueId, Field.ASSIGNEE, Long.toString(accountId), null, actorId, now);
        }
        return cleared.size();
    }
}
