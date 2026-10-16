package io.popdesk.helpdesk.comment;

import io.popdesk.ApiError;
import io.popdesk.ApiException;
import io.popdesk.PageRequest;
import io.popdesk.Paged;
import io.popdesk.TextInput;
import io.popdesk.Transactions;
import io.popdesk.helpdesk.AccountRateLimit;
import io.popdesk.helpdesk.IssueStatus;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.comment.CommentRepository.CommentRow;
import io.popdesk.helpdesk.company.CompanyRepository;
import io.popdesk.helpdesk.company.CompanyRepository.StaffRow;
import io.popdesk.helpdesk.issue.ImageIds;
import io.popdesk.helpdesk.issue.IssueAttachments;
import io.popdesk.helpdesk.issue.IssueKey;
import io.popdesk.helpdesk.issue.IssueNotifications;
import io.popdesk.helpdesk.issue.IssueRepository;
import io.popdesk.helpdesk.issue.IssueRepository.IssueRow;
import io.popdesk.helpdesk.issue.ReporterAccess;
import io.popdesk.helpdesk.sla.SlaPolicies;
import io.popdesk.helpdesk.sla.SlaWrite;
import io.popdesk.notification.WebhookKind;
import io.popdesk.notification.WebhookQueue;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Comments on issues (CMT-1 to CMT-4, MNT-1). Checks follow conventions "Order of checks": the rate
 * limit, the issue and the comment in the caller's scope (404), permission (403), input (400), then the
 * state of the comment and of the issue (400).
 */
@Service
public class CommentService {

    static final int CONTENT_MAX = 4_000;

    /** The most accounts one comment mentions (MNT-1). */
    static final int MENTIONS_MAX = 10;

    /** What an edit did to the mentions of a comment: the accounts new to the list and those taken out. */
    record MentionChange(List<Long> added, List<Long> removed) {

        static final MentionChange NONE = new MentionChange(List.of(), List.of());

        boolean empty() {
            return added.isEmpty() && removed.isEmpty();
        }
    }

    private final CommentRepository comments;
    private final IssueRepository issues;
    private final ReporterAccess reporters;
    private final CompanyRepository companies;
    private final IssueAttachments attachments;
    private final IssueNotifications notifications;
    private final WebhookQueue webhooks;
    private final AccountRateLimit rateLimit;
    private final Transactions transactions;
    private final SlaPolicies sla;
    private final Clock clock;

    CommentService(CommentRepository comments, IssueRepository issues, ReporterAccess reporters,
            CompanyRepository companies, IssueAttachments attachments, IssueNotifications notifications,
            WebhookQueue webhooks, AccountRateLimit rateLimit, Transactions transactions, SlaPolicies sla,
            Clock clock) {
        this.comments = comments;
        this.issues = issues;
        this.reporters = reporters;
        this.companies = companies;
        this.attachments = attachments;
        this.notifications = notifications;
        this.webhooks = webhooks;
        this.rateLimit = rateLimit;
        this.transactions = transactions;
        this.sla = sla;
        this.clock = clock;
    }

    /**
     * Adds a comment. Customers add public comments to issues they reported while they still belong to
     * the company (S0-AC-52, PROJ-4); staff add public or internal comments to issues they can see.
     *
     * <p>One transaction, retried once on a lock conflict, in the lock order of architecture.md: the
     * issue row {@code FOR NO KEY UPDATE} before any INSERT (S0-AC-51), then the images the comment
     * attaches, to the ID its row is about to get (S5-AC-20), then the comment, its mentions,
     * the webhook row, and the inbox items and emails of whoever {@link IssueNotifications} tells. A
     * public staff comment sets the first response time once (S0-AC-46) and tells the reporter
     * (S0-AC-70); an internal one does neither (S0-AC-53). A customer's comment goes to the webhook.
     * For a public staff comment the SLA policy's version is held {@code FOR KEY SHARE} before the issue row
     * (slice 8a design 0절 8b), and the first one stops the first response goal of an issue under SLA in the same
     * UPDATE as the first response time (S8A-AC-13).
     *
     * @param mentions the accounts staff mention with the comment (S4-AC-19), or null; a customer's
     *     are ignored (S4-AC-25)
     * @param imageIds the temporary images to attach to the comment, as the request had them, or null
     */
    public CommentRow add(Viewer viewer, String key, String content, boolean internal, List<Long> mentions,
            JsonNode imageIds) {
        rateLimit.count(AccountRateLimit.Action.COMMENT, viewer.accountId());
        IssueKey issueKey = IssueKey.parse(key).orElseThrow(ApiException::notFound);
        boolean answer = viewer.staff() && !internal;
        return transactions.run(() -> {
            SlaWrite slaWrite = answer ? sla.begin() : null;
            IssueRow issue = issues.lockByKey(viewer, issueKey).orElseThrow(ApiException::notFound);
            if (viewer.customer() && (internal || !reporters.writes(viewer, issue))) {
                throw ApiException.forbidden();
            }
            List<ApiError.FieldError> errors = new ArrayList<>();
            String text = TextInput.multiLine("content", content, true, CONTENT_MAX, errors);
            Set<Long> mentioned = viewer.staff() && mentions != null
                    ? mentionable(viewer, issue, internal, mentions, List.of(), errors) : Set.of();
            ImageIds images = ImageIds.of(imageIds, errors);
            if (!errors.isEmpty()) {
                throw ApiException.invalid(errors);
            }
            if (issue.status() == IssueStatus.CLOSED) {
                throw issueClosed();
            }
            Instant now = now();
            long id = comments.nextId();
            attachments.attachImages(viewer.accountId(), issue.id(), id, internal, false, images, now);
            comments.insert(id, issue.id(), viewer.accountId(), internal, text, now);
            comments.insertMentions(id, mentioned, now);
            if (viewer.customer()) {
                webhooks.queue(WebhookKind.CUSTOMER_COMMENT, issue.id(), id);
            }
            notifications.commented(viewer, issue, id, internal, mentioned);
            if (answer) {
                issues.updateFirstResponse(issue.id(), now, issue.sla() == null || issue.firstResponseAt() != null
                        ? null : slaWrite.answered(issue.sla(), issue.priority(), now));
            }
            return comments.find(viewer, issue.id(), id).orElseThrow();
        });
    }

    /**
     * The author changes a comment (CMT-3): its content, and for staff the accounts it mentions. A field
     * that is null was left out and stays; {@code content} sent as JSON {@code null} is refused, and
     * {@code mentions} sent as {@code null} or an empty list mentions nobody (S4-AC-24). Counted with
     * {@link #add} toward one limit (S4-AC-09). Of the mentions only the accounts new to the list must be
     * ones the author can mention now; an account the comment mentions already may stay in the list
     * after it was suspended or taken off the company, and gets nothing new (S4-AC-26). The limit of
     * {@value #MENTIONS_MAX} counts the whole list.
     *
     * <p>One transaction, retried once on a lock conflict: the issue row {@code FOR NO KEY UPDATE} as in
     * {@link #add}, then the comment in the caller's scope (404, S4-AC-03), the author alone, and a
     * customer only while they still belong to the company (403, S4-AC-02, S4-AC-05), the input (400),
     * then a deleted comment before a closed issue (S4-AC-08, S4-AC-04). The content the comment
     * already has leaves the time of the edit as it is, and so does a change of the mentions alone
     * (S4-AC-06). Whether the comment is internal never changes (S4-AC-07). Images are attached to
     * the comment before anything else is written, and attaching alone is no edit: the time of the edit
     * stays as it is (S5-AC-20).
     *
     * @param id the comment's ID as the path had it; one that is no number is an ID no comment has
     * @param mentions the whole list from now on; a customer's is ignored (S4-AC-25)
     * @param imageIds the temporary images to attach to the comment, as the request had them, or null
     */
    public CommentRow edit(Viewer viewer, String key, String id, JsonNode content, JsonNode mentions,
            JsonNode imageIds) {
        rateLimit.count(AccountRateLimit.Action.COMMENT, viewer.accountId());
        IssueKey issueKey = IssueKey.parse(key).orElseThrow(ApiException::notFound);
        long commentId = commentId(id);
        return transactions.run(() -> {
            IssueRow issue = issues.lockByKey(viewer, issueKey).orElseThrow(ApiException::notFound);
            CommentRow comment = comments.find(viewer, issue.id(), commentId).orElseThrow(ApiException::notFound);
            if (comment.authorId() != viewer.accountId()
                    || viewer.customer() && !reporters.writes(viewer, issue)) {
                throw ApiException.forbidden();
            }
            List<ApiError.FieldError> errors = new ArrayList<>();
            String text = content == null ? comment.content() : content(content, errors);
            List<Long> mentionedNow = List.of();
            Set<Long> mentioned = null;
            if (viewer.staff() && mentions != null) {
                List<Long> received = accountIds(mentions, errors);
                mentionedNow = comments.mentionedIds(commentId);
                mentioned = received == null ? null
                        : mentionable(viewer, issue, comment.internal(), received, mentionedNow, errors);
            }
            ImageIds images = ImageIds.of(imageIds, errors);
            if (!errors.isEmpty()) {
                throw ApiException.invalid(errors);
            }
            if (comment.deleted()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "COMMENT_DELETED", "This comment is deleted.");
            }
            if (issue.status() == IssueStatus.CLOSED) {
                throw issueClosed();
            }
            boolean contentChanged = !text.equals(comment.content());
            MentionChange change = mentioned == null ? MentionChange.NONE : mentionChange(mentionedNow, mentioned);
            Instant now = now();
            attachments.attachImages(viewer.accountId(), issue.id(), commentId, comment.internal(), false, images,
                    now);
            if (!contentChanged && change.empty()) {
                return comment;
            }
            if (contentChanged) {
                comments.updateContent(commentId, text, now);
            }
            comments.deleteMentions(commentId, change.removed());
            comments.insertMentions(commentId, change.added(), now);
            notifications.mentionsChanged(viewer, issue, commentId, change.added(), change.removed());
            return comments.find(viewer, issue.id(), commentId).orElseThrow();
        });
    }

    /**
     * Deletes a comment (CMT-3, COM-7): its author or an admin, on a closed issue too (S4-AC-13), and
     * a customer only while they still belong to the company (S4-AC-05). The row stays as a trace with
     * an empty content, the time, and who deleted it (S4-AC-10); its attachments are deleted like
     * attachments deleted alone (S4-AC-14). A comment that is deleted already stays as it is
     * (S4-AC-12). The first response time of the issue and the rows waiting in the outbox are not
     * touched (S4-AC-17). Not counted toward the limit of {@link #add} (S4-AC-09).
     *
     * <p>One transaction, retried once on a lock conflict, in the lock order of architecture.md: the
     * issue row {@code FOR NO KEY UPDATE}, the attachment rows in ID order, then the comment. The files
     * go after it is committed.
     *
     * @param id the comment's ID as the path had it; one that is no number is an ID no comment has
     */
    public void delete(Viewer viewer, String key, String id) {
        IssueKey issueKey = IssueKey.parse(key).orElseThrow(ApiException::notFound);
        long commentId = commentId(id);
        List<UUID> files = transactions.run(() -> {
            IssueRow issue = issues.lockByKey(viewer, issueKey).orElseThrow(ApiException::notFound);
            CommentRow comment = comments.find(viewer, issue.id(), commentId).orElseThrow(ApiException::notFound);
            if (!viewer.admin() && comment.authorId() != viewer.accountId()
                    || viewer.customer() && !reporters.writes(viewer, issue)) {
                throw ApiException.forbidden();
            }
            if (comment.deleted()) {
                return List.<UUID>of();
            }
            Instant now = now();
            List<UUID> purging = attachments.purgeOfComment(issue.id(), commentId, viewer.accountId(), now);
            comments.updateDeleted(commentId, viewer.accountId(), now);
            notifications.commentDeleted(commentId);
            return purging;
        });
        attachments.removeFiles(files);
    }

    /** One page of the comments the viewer may see on the issue, oldest first (S0-AC-54). */
    @Transactional(readOnly = true)
    public Paged<CommentRow> list(Viewer viewer, String key, int page, int size) {
        IssueRow issue = IssueKey.parse(key).flatMap(k -> issues.findByKey(viewer, k))
                .orElseThrow(ApiException::notFound);
        PageRequest request = PageRequest.of(page, size);
        return request.of(comments.list(viewer, issue.id(), request.offset(), request.size()),
                comments.count(viewer, issue.id()));
    }

    /** The comment ID written as {@code id}: a positive number that fits a {@code bigint}; else 404. */
    private static long commentId(String id) {
        if (id == null || !id.matches("[1-9][0-9]{0,18}")) {
            throw ApiException.notFound();
        }
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw ApiException.notFound();
        }
    }

    /** The content of an edit by the rules of adding; JSON {@code null} is refused like an empty text. */
    private static String content(JsonNode sent, List<ApiError.FieldError> errors) {
        if (!sent.isNull() && !sent.isValueNode()) {
            errors.add(new ApiError.FieldError("content", "INVALID_FORMAT"));
            return null;
        }
        return TextInput.multiLine("content", sent.isNull() ? null : sent.asString(), true, CONTENT_MAX, errors);
    }

    /**
     * The account IDs of {@code mentions} in an edit: JSON {@code null} is an empty list; anything but
     * a list of whole numbers adds {@code INVALID_FORMAT} and gives null.
     */
    private static List<Long> accountIds(JsonNode sent, List<ApiError.FieldError> errors) {
        List<Long> ids = new ArrayList<>();
        if (sent.isNull()) {
            return ids;
        }
        boolean numbers = sent.isArray();
        if (numbers) {
            for (JsonNode element : sent.values()) {
                if (element.isIntegralNumber() && element.canConvertToLong()) {
                    ids.add(element.asLong());
                } else {
                    numbers = false;
                }
            }
        }
        if (!numbers) {
            errors.add(new ApiError.FieldError("mentions", "INVALID_FORMAT"));
            return null;
        }
        return ids;
    }

    /**
     * The accounts to mention, each once (S4-AC-19), after the checks of MNT-1: more than
     * {@value #MENTIONS_MAX} is {@code TOO_MANY} (S4-AC-21), and an account the viewer cannot mention in
     * this kind of comment is {@code UNKNOWN_VALUE}, one answer whatever the reason (S4-AC-20). Who can
     * be mentioned is read after the issue row was locked, without locking account or assignment rows.
     * An invalid list adds its entry to {@code errors} and gives an empty set.
     *
     * @param kept the accounts the comment mentions already, which are not checked again; empty for a
     *     new comment
     */
    private Set<Long> mentionable(Viewer viewer, IssueRow issue, boolean internal, List<Long> received,
            List<Long> kept, List<ApiError.FieldError> errors) {
        if (received.stream().anyMatch(Objects::isNull)) {
            errors.add(new ApiError.FieldError("mentions", "INVALID_FORMAT"));
            return Set.of();
        }
        Set<Long> ids = new LinkedHashSet<>(received);
        if (ids.size() > MENTIONS_MAX) {
            errors.add(new ApiError.FieldError("mentions", "TOO_MANY"));
            return Set.of();
        }
        Set<Long> added = ids.stream().filter(id -> !kept.contains(id)).collect(Collectors.toSet());
        if (added.isEmpty()) {
            return ids;
        }
        Set<Long> candidates = companies
                .listMentionable(viewer, issue.companyId(), internal ? null : issue.reporterId()).stream()
                .map(StaffRow::accountId).collect(Collectors.toSet());
        if (!candidates.containsAll(added)) {
            errors.add(new ApiError.FieldError("mentions", "UNKNOWN_VALUE"));
            return Set.of();
        }
        return ids;
    }

    private static MentionChange mentionChange(List<Long> now, Set<Long> wanted) {
        return new MentionChange(wanted.stream().filter(id -> !now.contains(id)).toList(),
                now.stream().filter(id -> !wanted.contains(id)).toList());
    }

    private static ApiException issueClosed() {
        return new ApiException(HttpStatus.BAD_REQUEST, "ISSUE_CLOSED", "This issue is closed.");
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
