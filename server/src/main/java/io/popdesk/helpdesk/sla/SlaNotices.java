package io.popdesk.helpdesk.sla;

import io.popdesk.helpdesk.sla.SlaAlerts.Mark;
import java.util.List;
import java.util.UUID;

/**
 * Tells staff of the SLA goals the alert step found in warning or overdue (S8B-AC-01 to S8B-AC-03). The issue
 * package decides who is told, as it does for every other event of an issue, and implements this; the alert step
 * calls it once per batch, in the batch's transaction, after the marks are written.
 */
public interface SlaNotices {

    /** One of the two goals of an issue. */
    enum Goal {
        FIRST_RESPONSE,
        RESOLUTION
    }

    /**
     * One step of one goal to tell.
     *
     * @param assigneeId the issue's assignee, or null
     * @param step {@link Mark#WARNING} or {@link Mark#BREACHED}
     */
    record Alert(UUID issueId, long companyId, Long assigneeId, Goal goal, Mark step) {}

    /**
     * Tells each alert to the people S8B-AC-03 names, reading who they are once for the whole batch. Joins the
     * caller's transaction.
     */
    void tell(List<Alert> alerts);
}
