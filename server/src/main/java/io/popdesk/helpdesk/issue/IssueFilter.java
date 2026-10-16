package io.popdesk.helpdesk.issue;

import io.popdesk.helpdesk.IssueStatus;
import java.time.Instant;
import java.util.List;

/**
 * What narrows an issue list inside the viewer's scope (ISSUE-5). Every part that is set must match;
 * a null part, or an empty list of statuses, leaves that one out. Checked values only:
 * {@link IssueService#list} makes it from what a request sent.
 *
 * @param statuses the issue has one of these
 * @param companyCode the issue is of the company with this code
 * @param assignee who is assigned
 * @param reporterId this account reported the issue
 * @param q the title or the shown content holds this text, searched as it is (COM-5)
 * @param labelIds the issue has one of these labels; empty leaves this part out (S6A-AC-51)
 * @param sla the SLA state the issue is in (S8A-AC-31)
 */
public record IssueFilter(List<IssueStatus> statuses, String companyCode, Assignee assignee, Long reporterId,
        String q, List<Long> labelIds, Sla sla) {

    /**
     * The SLA state an issue must be in at {@code now}, by the worse of its running goals (S8A-AC-31, acceptance.md
     * 5절 12): {@link Kind#BREACHED} when one is past its due time, {@link Kind#WARNING} when none is and one is
     * past its warning time.
     */
    public record Sla(Kind kind, Instant now) {

        public enum Kind {
            WARNING,
            BREACHED
        }
    }

    /** The assignee an issue must have. {@code accountId} is set for {@link Kind#ACCOUNT} only. */
    public record Assignee(Kind kind, Long accountId) {

        public enum Kind {
            /** This account is assigned. */
            ACCOUNT,
            /** Nobody is assigned. */
            NONE,
            /** The assignee can no longer see the issue (ISSUE-11). */
            UNAVAILABLE
        }
    }
}
