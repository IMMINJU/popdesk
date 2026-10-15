package io.popdesk.helpdesk.issue;

import io.popdesk.helpdesk.AccountStatus;
import io.popdesk.helpdesk.IssueStatus;
import io.popdesk.helpdesk.LinkedIssues;
import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.account.AccountRepository;
import io.popdesk.helpdesk.account.AccountRepository.AccountRow;
import io.popdesk.helpdesk.issue.IssueRepository.IssueRow;
import io.popdesk.notification.IssueInbox;
import io.popdesk.notification.IssueInbox.Kind;
import io.popdesk.notification.MailKind;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who is told what when something happens on an issue (INBOX-1, INBOX-2): the one place that decides
 * it. A service says what happened and who did it; this class names the people by the table below,
 * leaves out those who are not to be told, and calls {@link IssueInbox} once for each who is left,
 * which adds the inbox item and, where the table has one, queues the email.
 *
 * <table>
 *   <caption>Events</caption>
 *   <tr><th>What happened</th><th>Who is told</th><th>Item</th><th>Email</th></tr>
 *   <tr><td>a public comment by staff</td><td>the reporter</td><td>STAFF_COMMENT</td><td>yes</td></tr>
 *   <tr><td>a comment by a customer</td><td>the assignee</td><td>CUSTOMER_COMMENT</td><td>no</td></tr>
 *   <tr><td>staff resolve, close, reopen</td><td>the reporter</td>
 *       <td>ISSUE_RESOLVED, ISSUE_CLOSED, ISSUE_REOPENED</td><td>yes</td></tr>
 *   <tr><td>a customer closes, reopens</td><td>the assignee</td><td>ISSUE_CLOSED, ISSUE_REOPENED</td>
 *       <td>no</td></tr>
 *   <tr><td>the assignee is set or changed</td><td>the new assignee</td><td>ASSIGNED</td><td>yes</td></tr>
 *   <tr><td>staff file for a customer</td><td>the reporter</td><td>ISSUE_FILED_ON_BEHALF</td><td>yes</td></tr>
 *   <tr><td>a comment mentions someone</td><td>that person</td><td>MENTIONED</td><td>yes</td></tr>
 *   <tr><td>a task gets another assignee</td><td>the task's new assignee</td><td>TASK_ASSIGNED</td>
 *       <td>yes</td></tr>
 *   <tr><td>a linked issue is resolved or closed</td><td>the assignee of each issue linked to it</td>
 *       <td>LINKED_ISSUE_RESOLVED, LINKED_ISSUE_CLOSED</td><td>no</td></tr>
 * </table>
 *
 * <p>Left out, in this order (S4-AC-34 to S4-AC-36): the person who did it; an account that is not
 * {@code ACTIVE}; an account that cannot see the issue now, by the scope of the API. And a person one
 * request both mentions and tells of the comment gets the mention alone (S4-AC-35, S4-AC-50). So an
 * issue without an assignee, or with one who is suspended or off the company, tells nobody of what the
 * customer did.
 *
 * <p>Every method joins the caller's transaction and is called only after the change is written, so a
 * refused request and one that changed nothing tell nobody (S4-AC-37). The rows are new rows without
 * foreign keys: step 3 of the lock order in architecture.md.
 */
@Service
public class IssueNotifications {

    /** One thing to tell one person: the kind of the inbox item, and the email with it or null. */
    private record Notice(long recipientId, Kind kind, MailKind mail) {}

    private final IssueInbox inbox;
    private final AccountRepository accounts;
    private final IssueRepository issues;
    private final LinkedIssues linked;

    IssueNotifications(IssueInbox inbox, AccountRepository accounts, IssueRepository issues, LinkedIssues linked) {
        this.inbox = inbox;
        this.accounts = accounts;
        this.issues = issues;
        this.linked = linked;
    }

    /**
     * A comment was added. A customer's goes to the assignee; a public one by staff to the reporter; an
     * internal one to nobody but the accounts it mentions (S4-AC-27, S4-AC-28, S4-AC-33).
     *
     * @param mentioned the accounts the comment mentions; empty for a customer's comment
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void commented(Viewer actor, IssueRow issue, long commentId, boolean internal, Collection<Long> mentioned) {
        List<Notice> notices = new ArrayList<>(mentions(mentioned));
        if (actor.customer()) {
            if (issue.assignee() != null) {
                notices.add(new Notice(issue.assignee().accountId(), Kind.CUSTOMER_COMMENT, null));
            }
        } else if (!internal) {
            notices.add(new Notice(issue.reporterId(), Kind.STAFF_COMMENT, MailKind.STAFF_COMMENT));
        }
        tell(actor, issue.id(), commentId, notices, account -> sees(account, issue.id()));
    }

    /**
     * An edit changed whom a comment mentions (S4-AC-24): the accounts new to the list are told; those
     * that were there before are not told again. An account taken out loses its unread item about the
     * mention, and the email is dropped when it is sent (S4-AC-56).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void mentionsChanged(Viewer actor, IssueRow issue, long commentId, Collection<Long> added,
            Collection<Long> removed) {
        tell(actor, issue.id(), commentId, mentions(added), account -> sees(account, issue.id()));
        for (long accountId : removed.stream().sorted().toList()) {
            inbox.deleteUnreadMention(commentId, accountId);
        }
    }

    /** A comment was deleted: every item about it goes, read or not (S4-AC-16). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void commentDeleted(long commentId) {
        inbox.deleteOfComment(commentId);
    }

    /**
     * The status changed to {@code to}. Staff resolving, closing, or reopening ({@code RECEIVED}) tell
     * the reporter; the reporter closing or reopening tells the assignee, without an email. Any other
     * status tells nobody (S4-AC-29, S4-AC-30, S4-AC-48).
     *
     * @param issue the issue as it is after the change
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void statusChanged(Viewer actor, IssueRow issue, IssueStatus to) {
        List<Notice> notices = new ArrayList<>();
        if (actor.staff()) {
            switch (to) {
                case RESOLVED -> notices.add(new Notice(issue.reporterId(), Kind.ISSUE_RESOLVED,
                        MailKind.ISSUE_RESOLVED));
                case CLOSED -> notices.add(new Notice(issue.reporterId(), Kind.ISSUE_CLOSED, MailKind.ISSUE_CLOSED));
                case RECEIVED -> notices.add(new Notice(issue.reporterId(), Kind.ISSUE_REOPENED,
                        MailKind.ISSUE_REOPENED));
                default -> { }
            }
        } else if (issue.assignee() != null) {
            switch (to) {
                case CLOSED -> notices.add(new Notice(issue.assignee().accountId(), Kind.ISSUE_CLOSED, null));
                case RECEIVED -> notices.add(new Notice(issue.assignee().accountId(), Kind.ISSUE_REOPENED, null));
                default -> { }
            }
        }
        tell(actor, issue.id(), null, notices, account -> sees(account, issue.id()));
    }

    /**
     * The issue became {@code RESOLVED} or {@code CLOSED}, by staff or by its reporter (LINK-6, S6C-AC-31):
     * the assignee of each issue linked to it gets an inbox item about their own issue, without an email.
     * Left out (S6C-AC-32): an issue without an assignee or that is {@code CLOSED}; the person who changed
     * the status; an account that is not {@code ACTIVE}; and one that cannot see both issues now. The links
     * are read whoever may see them, so a customer closing tells staff too. One item per linked issue, so a
     * person who is the assignee of two of them gets two. Any other status tells nobody.
     *
     * @param issue the issue as it is after the change
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void linkedStatusChanged(Viewer actor, IssueRow issue, IssueStatus to) {
        Kind kind = switch (to) {
            case RESOLVED -> Kind.LINKED_ISSUE_RESOLVED;
            case CLOSED -> Kind.LINKED_ISSUE_CLOSED;
            default -> null;
        };
        if (kind == null) {
            return;
        }
        for (IssueRepository.Assignment own : issues.assignments(linked.of(issue.id()))) {
            Long assigneeId = own.assigneeId();
            if (assigneeId == null || own.status() == IssueStatus.CLOSED || assigneeId == actor.accountId()) {
                continue;
            }
            AccountRow account = accounts.findById(assigneeId).orElse(null);
            if (account != null && account.status() == AccountStatus.ACTIVE && account.role() != Role.CUSTOMER
                    && sees(account, own.issueId()) && sees(account, issue.id())) {
                inbox.addAboutLinkedIssue(kind, account.id(), own.issueId(), issue.id(), actor.accountId());
            }
        }
    }

    /**
     * The issue got this assignee (S4-AC-31, S4-AC-49). Clearing the assignee is no event, and nobody
     * tells the assignee before.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void assigned(Viewer actor, IssueRow issue, long assigneeId) {
        tell(actor, issue.id(), null, List.of(new Notice(assigneeId, Kind.ASSIGNED, MailKind.ASSIGNED)),
                account -> sees(account, issue.id()));
    }

    /**
     * Staff are filing this issue for the reporter (S4-AC-32). The filing writes its queue rows before
     * the issue row (lock order), so the issue cannot be looked up yet; its reporter sees it by the
     * first rule of the scope, whatever else is true.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void filedOnBehalf(Viewer actor, UUID issueId, long reporterId) {
        tell(actor, issueId, null, List.of(new Notice(reporterId, Kind.ISSUE_FILED_ON_BEHALF,
                MailKind.ISSUE_FILED_ON_BEHALF)), account -> account.id() == reporterId);
    }

    /**
     * A task of the issue got this assignee, at its creation or by an edit (S6B-AC-33): the new assignee
     * alone is told, unless they made the change. Clearing the assignee and every other change of a task
     * tell nobody, and nothing goes to the webhook or to a customer (S6B-AC-35).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void taskAssigned(Viewer actor, IssueRow issue, long taskId, long assigneeId) {
        if (assigneeId == actor.accountId()) {
            return;
        }
        AccountRow account = accounts.findById(assigneeId).orElse(null);
        if (account != null && account.status() == AccountStatus.ACTIVE && account.role() != Role.CUSTOMER
                && sees(account, issue.id())) {
            inbox.addAboutTask(account.id(), issue.id(), taskId, actor.accountId());
        }
    }

    /** A task was deleted: every item about it goes, read or not (S6B-AC-36). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void taskDeleted(long taskId) {
        inbox.deleteOfTask(taskId);
    }

    private static List<Notice> mentions(Collection<Long> accountIds) {
        return accountIds.stream().sorted().map(id -> new Notice(id, Kind.MENTIONED, MailKind.MENTIONED)).toList();
    }

    /**
     * Tells the people of {@code notices} who are to be told: not the actor, only {@code ACTIVE}
     * accounts that see the issue, and of a mention and another notice for one person the mention alone.
     */
    private void tell(Viewer actor, UUID issueId, Long commentId, List<Notice> notices, Predicate<AccountRow> sees) {
        Set<Long> mentioned = notices.stream().filter(n -> n.kind() == Kind.MENTIONED).map(Notice::recipientId)
                .collect(Collectors.toSet());
        for (Notice notice : notices) {
            if (notice.recipientId() == actor.accountId()
                    || notice.kind() != Kind.MENTIONED && mentioned.contains(notice.recipientId())) {
                continue;
            }
            AccountRow account = accounts.findById(notice.recipientId()).orElse(null);
            if (account != null && account.status() == AccountStatus.ACTIVE && sees.test(account)) {
                inbox.add(notice.kind(), account.id(), issueId, commentId, actor.accountId(), notice.mail());
            }
        }
    }

    private boolean sees(AccountRow account, UUID issueId) {
        return issues.visible(new Viewer(account.id(), account.role()), issueId);
    }
}
