package io.popdesk.helpdesk.trash;

import io.popdesk.ApiException;
import io.popdesk.PageRequest;
import io.popdesk.Paged;
import io.popdesk.Transactions;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.attachment.AttachmentService;
import io.popdesk.helpdesk.audit.AuditAction;
import io.popdesk.helpdesk.audit.AuditLog;
import io.popdesk.helpdesk.comment.CommentRepository;
import io.popdesk.helpdesk.issue.IdempotencyKeyRepository;
import io.popdesk.helpdesk.issue.IssueHistoryRepository;
import io.popdesk.helpdesk.issue.IssueHistoryRepository.Field;
import io.popdesk.helpdesk.issue.IssueKey;
import io.popdesk.helpdesk.issue.IssueRepository;
import io.popdesk.helpdesk.issue.IssueRepository.IssueRow;
import io.popdesk.helpdesk.issue.IssueRepository.TrashedRow;
import io.popdesk.helpdesk.label.LabelRepository;
import io.popdesk.helpdesk.link.LinkRepository;
import io.popdesk.helpdesk.link.LinkRules.Link;
import io.popdesk.helpdesk.link.RestoreLinkCheck;
import io.popdesk.helpdesk.sla.SlaPolicies;
import io.popdesk.helpdesk.sla.SlaWrite;
import io.popdesk.helpdesk.task.TaskRepository;
import io.popdesk.notification.IssueInbox;
import io.popdesk.notification.MailQueue;
import io.popdesk.notification.WebhookQueue;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The trash (TRASH-1 to TRASH-4, slice 7a design sections 3 and 4): an admin deletes an issue into it,
 * lists it, restores an issue from it, or purges one at once, and popdesk purges what is 30 days old.
 * Every step is for admins only, checked before the issue (S7A-AC-02).
 *
 * <p>Each write locks the issue row {@code FOR NO KEY UPDATE} first and looks at whether the issue is in
 * the trash under that lock, so a restore and a purge of one issue end as if one came first
 * (S7A-AC-34, TM-61). A delete locks it in the viewer's scope, which leaves out an issue in the trash: a
 * second delete is 404.
 */
@Service
public class TrashService {

    /** How many issues one round of the purge job reads at a time; each is purged in a transaction of its own. */
    static final int PURGE_BATCH = 100;

    private final IssueRepository issues;
    private final IssueHistoryRepository history;
    private final AttachmentService attachments;
    private final CommentRepository comments;
    private final TaskRepository tasks;
    private final LinkRepository links;
    private final LabelRepository labels;
    private final IdempotencyKeyRepository keys;
    private final IssueInbox inbox;
    private final MailQueue mail;
    private final WebhookQueue webhooks;
    private final AuditLog audit;
    private final Transactions transactions;
    private final SlaPolicies sla;
    private final Clock clock;

    TrashService(IssueRepository issues, IssueHistoryRepository history, AttachmentService attachments,
            CommentRepository comments, TaskRepository tasks, LinkRepository links, LabelRepository labels,
            IdempotencyKeyRepository keys, IssueInbox inbox, MailQueue mail, WebhookQueue webhooks, AuditLog audit,
            Transactions transactions, SlaPolicies sla, Clock clock) {
        this.issues = issues;
        this.history = history;
        this.attachments = attachments;
        this.comments = comments;
        this.tasks = tasks;
        this.links = links;
        this.labels = labels;
        this.keys = keys;
        this.inbox = inbox;
        this.mail = mail;
        this.webhooks = webhooks;
        this.audit = audit;
        this.transactions = transactions;
        this.sla = sla;
        this.clock = clock;
    }

    /**
     * Moves an issue the admin sees to the trash, whatever its status (S7A-AC-01). One transaction,
     * retried once on a lock conflict: the issue row locked in the scope (404 for an issue in the trash or
     * purged, S7A-AC-03), then the time and the admin with a new version, then every row of the send queue
     * about it goes, so nothing about it is sent, not after a restore either (S7A-AC-10), then its history
     * entry (S7A-AC-06) and the audit entry (S7A-AC-07). Nothing under the issue changes. The SLA policy's
     * version is held {@code FOR KEY SHARE} before the issue row (slice 8a design 0절 8b), and the running SLA
     * goals pause in the same UPDATE (S8A-AC-16).
     */
    public void delete(Viewer viewer, AuditLog.Actor admin, String key) {
        adminOnly(viewer);
        IssueKey issueKey = IssueKey.parse(key).orElseThrow(ApiException::notFound);
        transactions.run(() -> {
            SlaWrite slaWrite = sla.begin();
            IssueRow issue = issues.lockByKey(viewer, issueKey).orElseThrow(ApiException::notFound);
            Instant now = now();
            issues.updateDeleted(issue.id(), viewer.accountId(), now,
                    issue.sla() == null ? null : slaWrite.deleted(issue.sla(), issue.priority(), now));
            mail.dropAbout(issue.id());
            webhooks.dropAbout(issue.id());
            history.insertChanged(issue.id(), Field.TRASH, null, Field.TRASHED, viewer.accountId(), now);
            audit.record(admin, AuditAction.ISSUE_DELETED, target(issue.companyCode(), issue.key()), null);
            return null;
        });
    }

    /** One page of the trash, the latest deleted first (S7A-AC-20); then 400 for a page that cannot be one. */
    @Transactional(readOnly = true)
    public Paged<TrashedRow> list(Viewer viewer, int page, int size) {
        adminOnly(viewer);
        PageRequest request = PageRequest.of(page, size);
        return request.of(issues.listTrash(viewer, request.offset(), request.size()), issues.countTrash(viewer));
    }

    /**
     * Brings an issue back from the trash as it was (S7A-AC-21). One transaction, retried once on a lock
     * conflict: the issue is found in the trash (404), then it and every issue linked to it are locked
     * in ID order in one statement, and only then is it looked at again: still in the trash, or 404
     * (S7A-AC-23, S7A-AC-34). With the links read under those locks, a link that would break the link
     * rules is 409 and nothing changes (S7A-AC-24). Then the issue is live with a new version, a
     * {@code RESOLVED} one closes 7 days from now (S7A-AC-22), and its history and audit entries follow.
     * The SLA policy's version is held {@code FOR KEY SHARE} before any issue row (slice 8a design 0절 8b), and the
     * SLA goals that ran when the issue was deleted run on from now in the same UPDATE (S8A-AC-16).
     *
     * @return the issue as it is now
     */
    public IssueRow restore(Viewer viewer, AuditLog.Actor admin, String key) {
        adminOnly(viewer);
        IssueKey issueKey = IssueKey.parse(key).orElseThrow(ApiException::notFound);
        return transactions.run(() -> {
            SlaWrite slaWrite = sla.begin();
            TrashedRow found = issues.findInTrash(viewer, issueKey).orElseThrow(ApiException::notFound);
            UUID id = found.id();
            Set<UUID> locking = new LinkedHashSet<>();
            locking.add(id);
            links.linksOf(id).forEach(l -> locking.add(l.otherThan(id)));
            Map<UUID, TrashedRow> locked = issues.lockTrashedByIds(viewer, locking).stream()
                    .collect(Collectors.toMap(TrashedRow::id, Function.identity()));
            TrashedRow issue = locked.get(id);
            if (issue == null || !issue.inTrash()) {
                throw ApiException.notFound();
            }
            List<Link> own = links.linksOf(id);
            Map<UUID, List<Link>> ofOthers = new HashMap<>();
            for (Link link : own) {
                ofOthers.computeIfAbsent(link.otherThan(id), links::linksOf);
            }
            List<Link> faults = RestoreLinkCheck.atFault(id, own, ofOthers);
            if (!faults.isEmpty()) {
                throw ApiException.restoreLinkConflict(faults.stream().map(l -> l.otherThan(id)).distinct()
                        .map(other -> locked.containsKey(other) ? locked.get(other).key() : null)
                        .filter(Objects::nonNull).toList());
            }
            Instant now = now();
            issues.updateRestored(id, now,
                    issue.sla() == null ? null : slaWrite.restored(issue.sla(), issue.priority(), now));
            history.insertChanged(id, Field.TRASH, Field.TRASHED, null, viewer.accountId(), now);
            audit.record(admin, AuditAction.ISSUE_RESTORED, target(issue.companyCode(), issue.key()), null);
            return issues.findById(viewer, id).orElseThrow();
        });
    }

    /**
     * Purges an issue in the trash at once (S7A-AC-30): the issue row locked and looked at again, still in
     * the trash or 404 (S7A-AC-23, S7A-AC-34); then everything written in it goes in that transaction,
     * with the audit entry (S7A-AC-07). Its files go after the commit (S7A-AC-33).
     */
    public void purge(Viewer viewer, AuditLog.Actor admin, String key) {
        adminOnly(viewer);
        IssueKey issueKey = IssueKey.parse(key).orElseThrow(ApiException::notFound);
        List<UUID> files = transactions.run(() -> {
            TrashedRow found = issues.findInTrash(viewer, issueKey).orElseThrow(ApiException::notFound);
            TrashedRow issue = issues.lockTrashedByIds(viewer, List.of(found.id())).stream().findFirst()
                    .filter(TrashedRow::inTrash).orElseThrow(ApiException::notFound);
            List<UUID> purging = empty(issue.id(), viewer.accountId(), viewer.accountId(), now());
            audit.record(admin, AuditAction.ISSUE_PURGED, target(issue.companyCode(), issue.key()), null);
            return purging;
        });
        attachments.removePurgedFiles(files);
    }

    /**
     * The purge job's work (S7A-AC-31): every issue deleted 30 days ago or more is purged as an admin's
     * purge does, by popdesk itself and without an audit entry (S7A-AC-07), one issue per transaction. The
     * issues are picked without a lock; each is locked and looked at again before it is emptied, so one
     * restored meanwhile, or one that is not due, is left as it is (S7A-AC-34).
     *
     * @param now the time of the run
     * @return how many issues were purged
     */
    public long purgeDue(Instant now) {
        Viewer system = Viewer.system();
        long purged = 0;
        List<UUID> due;
        do {
            due = issues.findDueForPurge(system, TrashTimes.deletedUntil(now), PURGE_BATCH);
            for (UUID id : due) {
                List<UUID> files = transactions.run(() -> {
                    TrashedRow issue = issues.lockTrashedByIds(system, List.of(id)).stream().findFirst()
                            .orElse(null);
                    if (issue == null || !issue.inTrash() || !TrashTimes.due(issue.deletedAt(), now)) {
                        return null;
                    }
                    return empty(id, null, system.accountId(), now);
                });
                if (files != null) {
                    purged++;
                    attachments.removePurgedFiles(files);
                }
            }
        } while (due.size() == PURGE_BATCH);
        return purged;
    }

    /**
     * The moves of automatic cleanup (S10B-AC-20, S10B-AC-21): every issue due with {@code period} is moved to the
     * trash by {@link #deleteBySystem}, one issue per transaction. The issues are picked without a lock, at most
     * {@value #PURGE_BATCH} at a time, as {@link #purgeDue} picks them.
     *
     * @param now the time of the run
     * @param period the period of {@code POPDESK_ISSUE_RETENTION}, positive
     * @return how many issues were moved
     */
    public long moveDue(Instant now, Duration period) {
        Instant startedBefore = now.minus(period);
        long moved = 0;
        List<UUID> due;
        int movedOfBatch;
        do {
            due = issues.findDueForRetention(startedBefore, PURGE_BATCH);
            movedOfBatch = 0;
            for (UUID id : due) {
                if (deleteBySystem(id, now, startedBefore)) {
                    movedOfBatch++;
                }
            }
            moved += movedOfBatch;
            // A batch of which nothing moved was changed meanwhile; the next run picks again.
        } while (due.size() == PURGE_BATCH && movedOfBatch > 0);
        return moved;
    }

    /**
     * Moves one issue to the trash as automatic cleanup (S10B-AC-20, acceptance.md 5절 4): what an admin's
     * {@link #delete} does without the admin and the audit entry. In a transaction of its own, the SLA policy's
     * version held first and then the issue row locked, as {@link #delete} takes them; then the issue is moved only
     * if it is still due under that lock (S10B-AC-21), with {@code deleted_by} null, its waiting messages dropped, and
     * a {@code TRASH} history entry by popdesk (account 0).
     *
     * @param startedBefore the latest time the issue's period may have started: the run's time less the period
     * @return whether the issue was moved
     */
    boolean deleteBySystem(UUID id, Instant now, Instant startedBefore) {
        Viewer system = Viewer.system();
        return transactions.run(() -> {
            SlaWrite slaWrite = sla.begin();
            TrashedRow issue = issues.lockTrashedByIds(system, List.of(id)).stream().findFirst().orElse(null);
            if (issue == null || issue.deletedAt() != null) {
                return false;
            }
            if (!issues.updateDeletedBySystem(id, now, startedBefore,
                    issue.sla() == null ? null : slaWrite.deleted(issue.sla(), issue.priority(), now))) {
                return false;
            }
            mail.dropAbout(id);
            webhooks.dropAbout(id);
            history.insertChanged(id, Field.TRASH, null, Field.TRASHED, system.accountId(), now);
            return true;
        });
    }

    /**
     * Empties the issue in the caller's transaction, under the lock of its row (slice 7a design section 4):
     * its attachments become {@code purging} first (lock order step 2), then its inbox items and the items
     * about it on other issues, its queue rows, comments and mentions, tasks and notes, links, labels, and
     * filing key go; the title and content values of its history are cleared; the issue keeps an empty
     * title and content; and a last {@code TRASH} entry says it was purged.
     *
     * @param deletedBy the admin who purges, or null for popdesk itself
     * @param actorId the history's actor: the admin, or 0
     * @return the attachments whose files go after the commit
     */
    private List<UUID> empty(UUID id, Long deletedBy, long actorId, Instant now) {
        List<UUID> files = attachments.purgeOfIssue(id, deletedBy, now);
        inbox.deleteOfIssue(id);
        mail.dropAbout(id);
        webhooks.dropAbout(id);
        comments.deleteOfIssue(id);
        tasks.deleteOfIssue(id);
        links.deleteOfIssue(id);
        labels.deleteOfIssue(id);
        keys.deleteOfIssue(id);
        history.updateTextsCleared(id);
        issues.updatePurged(id, now);
        history.insertChanged(id, Field.TRASH, Field.TRASHED, Field.PURGED, actorId, now);
        return files;
    }

    /**
     * The target of the audit entries (slice 7a acceptance section 5, 8): the company as every company
     * entry has it, so the audit log's company filter finds it, and the issue key. No title: the entry
     * stays after the issue is purged.
     */
    private static Map<String, Object> target(String code, String key) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("type", "COMPANY");
        target.put("code", code);
        target.put("issueKey", key);
        return target;
    }

    /** The security configuration lets only admins reach the trash; this holds should a path slip out of it. */
    private static void adminOnly(Viewer viewer) {
        if (!viewer.admin()) {
            throw ApiException.forbidden();
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
