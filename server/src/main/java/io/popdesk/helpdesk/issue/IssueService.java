package io.popdesk.helpdesk.issue;

import io.popdesk.ApiError;
import io.popdesk.ApiException;
import io.popdesk.InstallationZone;
import io.popdesk.PageRequest;
import io.popdesk.Paged;
import io.popdesk.PatchText;
import io.popdesk.TextInput;
import io.popdesk.Transactions;
import io.popdesk.helpdesk.AccountRateLimit;
import io.popdesk.helpdesk.DueDates;
import io.popdesk.helpdesk.CompanyStatus;
import io.popdesk.helpdesk.IssueStatus;
import io.popdesk.helpdesk.Priority;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.company.CompanyRepository;
import io.popdesk.helpdesk.company.CompanyRepository.CompanyRow;
import io.popdesk.helpdesk.company.CompanyRepository.StaffRow;
import io.popdesk.helpdesk.issue.IssueHistoryRepository.Field;
import io.popdesk.helpdesk.issue.IssueHistoryRepository.HistoryRow;
import io.popdesk.helpdesk.issue.IssueRepository.IssueRow;
import io.popdesk.helpdesk.label.LabelRepository;
import io.popdesk.helpdesk.label.LabelRepository.IssueLabel;
import io.popdesk.notification.WebhookKind;
import io.popdesk.notification.WebhookQueue;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Filing, listing, reading, and changing issues (ISSUE-1 to ISSUE-9, STATUS-1 to STATUS-7). Checks
 * follow conventions "Order of checks": the rate limit and sign-in first, then the target in the
 * caller's scope (404), then permission (403), then the input (400), then the state (400 or 409).
 *
 * <p>Filing is two calls so that attachments can be checked and written in between, outside the
 * filing transaction (architecture.md "Concurrency and lock order"): {@link #prepare} checks
 * everything and picks the issue ID, {@link #file} writes the issue.
 *
 * <p>A filing may come with an {@code Idempotency-Key} (ISSUE-12, slice 3 design section 4). When the
 * account used the key in the last 24 hours, {@link #prepare} answers with the issue that filing made
 * and nothing is written; else {@link #file} writes the key with the issue, and of two filings that
 * do so at once one finds the key taken and answers with the other's issue.
 */
@Service
public class IssueService {

    static final int TITLE_MAX = 200;
    static final int CONTENT_MAX = 10_000;
    static final int SEARCH_MAX = 100;
    static final String CHANNEL_PORTAL = "PORTAL";

    /** How a request staff file for a customer can have come in (ISSUE-9); the names are stored. */
    static final Set<String> CHANNELS_ON_BEHALF = Set.of("PHONE", "OTHER");

    /** The words the assignee filter of the list takes besides an account ID (S3-AC-03, S3-AC-04). */
    static final String ASSIGNEE_ME = "me";
    static final String ASSIGNEE_NONE = "none";
    static final String ASSIGNEE_UNAVAILABLE = "unavailable";

    /** How many labels an issue has at most (TM-56, S6A-AC-24). */
    static final int ISSUE_LABEL_MAX = 20;

    /** The values {@code sort} takes (S6A-AC-53); without it, {@link #SORT_CREATED_AT}. */
    static final String SORT_CREATED_AT = "createdAt";
    static final String SORT_DUE_DATE = "dueDate";

    /** An account ID as the assignee filter gets it: a positive number that fits a {@code bigint}. */
    private static final Pattern ACCOUNT_ID = Pattern.compile("[1-9][0-9]{0,17}");

    /**
     * How often a status change that lost against another one reads the issue again and tries once
     * more before it takes the row lock, under which the next try cannot lose.
     */
    private static final int STATUS_RETRIES = 3;

    /**
     * What a customer sent to file an issue, as received. {@code priority} is the name sent, or null;
     * {@code imageIds} the temporary images to attach to the new issue, or null.
     */
    public record NewIssue(String companyCode, String title, String content, String priority, Boolean privateIssue,
            JsonNode imageIds) {}

    /**
     * What staff sent to file an issue for a customer, as received (ISSUE-9): the fields of
     * {@link NewIssue}, the customer, and how the request came in.
     */
    public record NewIssueOnBehalf(NewIssue issue, Long reporterId, String channel) {}

    /**
     * A checked filing, not written yet. The ID is chosen now, so attachments can point at it first.
     * {@code createdBy} is the staff member who files it for the reporter; null when the reporter files it.
     * {@code key} is the key the filing was sent with, null without one. {@code images} are the
     * temporary images the filing attaches; they are no part of what makes it another filing (S5-AC-20).
     */
    public record Draft(UUID id, long reporterId, Long createdBy, String channel, CompanyRow company, String title,
            String content, Priority priority, boolean privateIssue, ImageIds images, IdempotencyKey key) {

        Draft with(IdempotencyKey key) {
            return new Draft(id, reporterId, createdBy, channel, company, title, content, priority, privateIssue,
                    images, key);
        }
    }

    /**
     * What a filing request comes to before anything is written: {@code filed}, the issue an earlier
     * request with the same key and content made, as it is now (S3-AC-41); or {@code draft}, a filing
     * to write. One of the two is null.
     */
    public record Prepared(IssueRow filed, Draft draft) {}

    /** The key of this filing is taken: another filing with it was committed while this one ran. */
    private static final class KeyTaken extends RuntimeException {

        KeyTaken() {
            super(null, null, false, false);
        }
    }

    /**
     * The filters of a list request, as received; a null one was not sent (ISSUE-5). {@code assignee}
     * is an account ID or one of the words {@code me}, {@code none}, {@code unavailable}; {@code q} is
     * the search text; {@code labelIds} the labels, any of which an issue must have (S6A-AC-51);
     * {@code sort} the order as sent (S6A-AC-53).
     */
    public record Filters(List<IssueStatus> statuses, String companyCode, String assignee, Long reporterId,
            String q, List<Long> labelIds, String sort) {

        /** The filters of a list that takes no label and no order: the portal's (S6A-AC-42). */
        public Filters(List<IssueStatus> statuses, String companyCode, String assignee, Long reporterId, String q) {
            this(statuses, companyCode, assignee, reporterId, q, null, null);
        }
    }

    /**
     * What an edit sent (S3-AC-18). A field that was not sent keeps its value; {@code version} is the
     * one the caller read, or null when it was left out. {@code imageIds} are the temporary images to
     * attach to the issue, or null.
     */
    public record Edit(PatchText title, PatchText content, PatchText priority, Long version, JsonNode imageIds) {}

    private final IssueRepository issues;
    private final IssueHistoryRepository history;
    private final LabelRepository labels;
    private final IdempotencyKeyRepository keys;
    private final ReporterAccess reporters;
    private final CompanyRepository companies;
    private final IssueAttachments attachments;
    private final WebhookQueue webhooks;
    private final IssueNotifications notifications;
    private final AccountRateLimit rateLimit;
    private final Transactions transactions;
    private final InstallationZone zone;
    private final Clock clock;

    IssueService(IssueRepository issues, IssueHistoryRepository history, LabelRepository labels,
            IdempotencyKeyRepository keys, ReporterAccess reporters, CompanyRepository companies,
            IssueAttachments attachments, WebhookQueue webhooks, IssueNotifications notifications,
            AccountRateLimit rateLimit, Transactions transactions, InstallationZone zone, Clock clock) {
        this.issues = issues;
        this.history = history;
        this.labels = labels;
        this.keys = keys;
        this.reporters = reporters;
        this.companies = companies;
        this.attachments = attachments;
        this.webhooks = webhooks;
        this.notifications = notifications;
        this.rateLimit = rateLimit;
        this.transactions = transactions;
        this.zone = zone;
        this.clock = clock;
    }

    /**
     * Checks a filing by a customer: counted against the rate limit (S0-AC-50), the company must be one
     * the customer belongs to (S0-AC-43, 404), every invalid field is listed (S0-AC-42), with them a
     * key that cannot be one (S3-AC-45), and the company must be active. Nothing is written.
     *
     * @param idempotencyKey the {@code Idempotency-Key} header as sent, or null
     */
    public Prepared prepare(Viewer viewer, NewIssue input, String idempotencyKey) {
        rateLimit.count(AccountRateLimit.Action.ISSUE, viewer.accountId());
        List<ApiError.FieldError> errors = new ArrayList<>();
        CompanyRow company = null;
        if (input.companyCode() == null || input.companyCode().isEmpty()) {
            errors.add(new ApiError.FieldError("companyCode", "REQUIRED"));
        } else {
            company = companies.findByCode(viewer, input.companyCode()).orElseThrow(ApiException::notFound);
        }
        String title = TextInput.required("title", input.title(), TITLE_MAX, errors);
        String content = TextInput.multiLine("content", input.content(), false, CONTENT_MAX, errors);
        Priority priority = input.priority() == null ? Priority.NORMAL : priority(input.priority(), errors);
        ImageIds images = ImageIds.of(input.imageIds(), errors);
        boolean keyed = idempotencyKey != null && IdempotencyKey.valid(idempotencyKey, errors);
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors);
        }
        return prepared(viewer, new Draft(UUID.randomUUID(), viewer.accountId(), null, CHANNEL_PORTAL, company, title,
                content, priority, Boolean.TRUE.equals(input.privateIssue()), images, null),
                keyed ? idempotencyKey : null);
    }

    /**
     * Checks a filing by staff for a customer (ISSUE-9), by the rules of {@link #prepare} and in the
     * same rate limit, counted before the company is looked up (S3-AC-39). The company must be one the
     * staff member sees (S3-AC-34, 404 whatever else is wrong). Every invalid field is listed, with them
     * a channel that is not one of {@link #CHANNELS_ON_BEHALF} (S3-AC-35) and a reporter who is not an
     * {@code ACTIVE} customer of the company, one answer whatever the reason (S3-AC-33). Then the
     * company must be active. Nothing is written; {@link #file} looks at the reporter again.
     *
     * @param idempotencyKey the {@code Idempotency-Key} header as sent, or null
     */
    public Prepared prepareOnBehalf(Viewer staff, NewIssueOnBehalf input, String idempotencyKey) {
        rateLimit.count(AccountRateLimit.Action.ISSUE, staff.accountId());
        NewIssue issue = input.issue();
        List<ApiError.FieldError> errors = new ArrayList<>();
        CompanyRow company = null;
        if (issue.companyCode() == null || issue.companyCode().isEmpty()) {
            errors.add(new ApiError.FieldError("companyCode", "REQUIRED"));
        } else {
            company = companies.findByCode(staff, issue.companyCode()).orElseThrow(ApiException::notFound);
        }
        if (input.reporterId() == null) {
            errors.add(new ApiError.FieldError("reporterId", "REQUIRED"));
        } else if (company != null && !companies.isReporter(staff, company.id(), input.reporterId())) {
            errors.add(unknownReporter());
        }
        if (input.channel() == null || input.channel().isEmpty()) {
            errors.add(new ApiError.FieldError("channel", "REQUIRED"));
        } else if (!CHANNELS_ON_BEHALF.contains(input.channel())) {
            errors.add(new ApiError.FieldError("channel", "UNKNOWN_VALUE"));
        }
        String title = TextInput.required("title", issue.title(), TITLE_MAX, errors);
        String content = TextInput.multiLine("content", issue.content(), false, CONTENT_MAX, errors);
        Priority priority = issue.priority() == null ? Priority.NORMAL : priority(issue.priority(), errors);
        ImageIds images = ImageIds.of(issue.imageIds(), errors);
        boolean keyed = idempotencyKey != null && IdempotencyKey.valid(idempotencyKey, errors);
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors);
        }
        return prepared(staff, new Draft(UUID.randomUUID(), input.reporterId(), staff.accountId(), input.channel(),
                company, title, content, priority, Boolean.TRUE.equals(issue.privateIssue()), images, null),
                keyed ? idempotencyKey : null);
    }

    /**
     * The last checks of a filing whose company and fields are fine. With a key, first what the account
     * did with it (slice 3 design section 4, step 1): the issue of an earlier filing with this content
     * is the answer, whatever happened to the company since, and whatever images this request names:
     * the first filing attached its own (acceptance.md of slice 5, section 5, 16). Then the company
     * must be active.
     */
    private Prepared prepared(Viewer viewer, Draft draft, String idempotencyKey) {
        Draft keyed = draft;
        if (idempotencyKey != null) {
            IdempotencyKey key = IdempotencyKey.of(idempotencyKey, draft);
            Optional<IssueRow> filed = filedWith(viewer, key);
            if (filed.isPresent()) {
                return new Prepared(filed.get(), null);
            }
            keyed = draft.with(key);
        }
        if (draft.company().status() != CompanyStatus.ACTIVE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "COMPANY_NOT_ACTIVE", "The company is not active.");
        }
        return new Prepared(null, keyed);
    }

    /**
     * The issue the account filed with this key in the last 24 hours, as it is now (S3-AC-41); empty
     * when it has not used the key, or longer ago (S3-AC-46). The key with other content is 409
     * (S3-AC-42). A key is the account's own (S3-AC-43), and the issue is read in the viewer's scope
     * like any other: one the viewer no longer sees is 404.
     */
    private Optional<IssueRow> filedWith(Viewer viewer, IdempotencyKey key) {
        Optional<IdempotencyKeyRepository.KeyRow> used = keys.find(viewer.accountId(), key.value(),
                now().minus(IdempotencyKey.KEPT));
        if (used.isEmpty()) {
            return Optional.empty();
        }
        if (!used.get().requestHash().equals(key.requestHash())) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was used for another filing.");
        }
        return Optional.of(issues.findById(viewer, used.get().issueId()).orElseThrow(ApiException::notFound));
    }

    /** Writes a prepared filing without attachments; see {@link #file(Viewer, Draft, Runnable)}. */
    public IssueRow file(Viewer viewer, Draft draft) {
        return file(viewer, draft, () -> {});
    }

    /**
     * Writes a prepared filing in one transaction, retried once on a lock conflict (S0-AC-85), in the
     * lock order of architecture.md: {@code storeAttachments} turns the filing's pending attachments
     * stored and the filing's temporary images are attached to the issue's ID (step 2, S5-AC-20), then
     * the key row and the webhook row, then the company counter, then the issue row
     * with the number it got (S0-AC-44), then the first entry of its history, by the person who filed
     * (S3-AC-61). A failed filing uses no number and keeps no key (S3-AC-47), and its attachments stay
     * pending (S0-AC-63).
     *
     * <p>The key row comes before the counter. When another filing with the key was committed first,
     * the row is not written; this transaction is rolled back before it takes a number, and the answer
     * is the other filing's issue, or 409 when its content differs (S3-AC-44).
     *
     * <p>A filing by staff for a customer goes the same way (ISSUE-9). Before anything is written the
     * reporter is looked at again, in this transaction: a customer who was suspended or taken out of the
     * company since {@link #prepareOnBehalf} gets nothing filed in their name (S3-AC-33). It posts the
     * same webhook message (S3-AC-75), and the reporter's inbox item and email are written with the other
     * new rows (S3-AC-37, S4-AC-32). The staff member is the one the history names.
     */
    public IssueRow file(Viewer viewer, Draft draft, Runnable storeAttachments) {
        try {
            return write(viewer, draft, storeAttachments);
        } catch (KeyTaken e) {
            return filedWith(viewer, draft.key())
                    .orElseThrow(() -> new IllegalStateException("The key of a filing was taken and is gone."));
        }
    }

    private IssueRow write(Viewer viewer, Draft draft, Runnable storeAttachments) {
        return transactions.run(() -> {
            Instant now = now();
            boolean onBehalf = draft.createdBy() != null;
            if (onBehalf && !companies.isReporter(viewer, draft.company().id(), draft.reporterId())) {
                throw ApiException.invalid(List.of(unknownReporter()));
            }
            storeAttachments.run();
            attachments.attachImages(viewer.accountId(), draft.id(), null, false, true, draft.images(), now);
            IdempotencyKey key = draft.key();
            if (key != null && !keys.insert(viewer.accountId(), key.value(), key.requestHash(), draft.id(), now,
                    now.minus(IdempotencyKey.KEPT))) {
                throw new KeyTaken();
            }
            webhooks.queue(WebhookKind.ISSUE_CREATED, draft.id(), null);
            if (onBehalf) {
                notifications.filedOnBehalf(viewer, draft.id(), draft.reporterId());
            }
            int number = companies.updateNextIssueNumber(draft.company().id());
            issues.insert(draft.id(), draft.company().id(), number, draft.title(), draft.content(), draft.priority(),
                    draft.privateIssue(), draft.reporterId(), draft.createdBy(), draft.channel(), now);
            history.insertCreated(draft.id(), viewer.accountId(), now);
            return issues.findByKey(viewer, new IssueKey(draft.company().code(), number)).orElseThrow();
        });
    }

    /**
     * Staff move an issue to one of the statuses its status allows ({@link IssueStatus#next}, S3-AC-48);
     * any other, and the status it has, is 400 {@code INVALID_TRANSITION} (S3-AC-49). The status comes as
     * the name sent, or null, so that a missing or unknown one is 400 only after the issue was found.
     */
    public IssueRow changeStatus(Viewer viewer, String key, String statusName) {
        return move(viewer, key, (issue, now) -> {
            if (statusName == null) {
                throw ApiException.invalid("status", "REQUIRED");
            }
            IssueStatus status;
            try {
                status = IssueStatus.valueOf(statusName);
            } catch (IllegalArgumentException e) {
                throw ApiException.invalid("status", "UNKNOWN_VALUE");
            }
            if (!issue.status().next().contains(status)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TRANSITION",
                        "The issue cannot go from its status to this one.");
            }
            return status;
        });
    }

    /**
     * The customer who reported an issue closes it from any status, which on a {@code RESOLVED} issue
     * confirms the resolution (S3-AC-53). Still theirs to close after they left the company (S3-AC-54).
     */
    public IssueRow close(Viewer viewer, String key) {
        return move(viewer, key, (issue, now) -> {
            if (issue.reporterId() != viewer.accountId()) {
                throw ApiException.forbidden();
            }
            if (issue.status() == IssueStatus.CLOSED) {
                throw closed();
            }
            return IssueStatus.CLOSED;
        });
    }

    /**
     * Staff, or the customer who reported the issue and still belongs to its company (S3-AC-58), take a
     * {@code RESOLVED} issue, or one closed less than 14 days ago, back to {@code RECEIVED} (S3-AC-56).
     */
    public IssueRow reopen(Viewer viewer, String key) {
        return move(viewer, key, (issue, now) -> {
            if (viewer.customer() && !reporters.writes(viewer, issue)) {
                throw ApiException.forbidden();
            }
            if (!issue.reopenable(now)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "NOT_REOPENABLE", "This issue cannot be reopened.");
            }
            return IssueStatus.RECEIVED;
        });
    }

    /**
     * A change of status in one transaction (STATUS-7, slice 3 design section 2): read the issue in the
     * caller's scope, let {@code target} check the caller and the status and name the new one, then
     * update the row on the condition that it still has the status read. When another change got there
     * first no row changes: the issue is read again and judged from the start, so the answer is the one
     * this request would get after that change, and a refused request leaves nothing. After a few lost
     * tries the row is locked, which is still before any INSERT. The history entry, the webhook row, and
     * the inbox item and email of whoever is told are written only after the UPDATE changed the row
     * (S3-AC-51, S3-AC-63, S3-AC-73, S4-AC-37).
     *
     * @param target given the issue and the time of this try, throws when the caller may not do this or
     *     the status does not allow it; else the status to set
     */
    private IssueRow move(Viewer viewer, String key, BiFunction<IssueRow, Instant, IssueStatus> target) {
        IssueKey issueKey = IssueKey.parse(key).orElseThrow(ApiException::notFound);
        return transactions.run(() -> {
            IssueRow issue = issues.findByKey(viewer, issueKey).orElseThrow(ApiException::notFound);
            int tries = 0;
            while (true) {
                Instant now = now();
                IssueStatus to = target.apply(issue, now);
                boolean reopening = to == IssueStatus.RECEIVED;
                if (issues.updateStatus(issue.id(), issue.status(), to, now,
                        reopening ? now.minus(IssueRepository.REOPEN_WINDOW) : null)) {
                    history.insertChanged(issue.id(), Field.STATUS, issue.status().name(), to.name(),
                            viewer.accountId(), now);
                    switch (to) {
                        case RESOLVED -> webhooks.queue(WebhookKind.ISSUE_RESOLVED, issue.id(), null);
                        case CLOSED -> webhooks.queue(WebhookKind.ISSUE_CLOSED, issue.id(), null);
                        case RECEIVED -> webhooks.queue(WebhookKind.ISSUE_REOPENED, issue.id(), null);
                        default -> { }
                    }
                    IssueRow moved = issues.findByKey(viewer, issueKey).orElseThrow();
                    notifications.statusChanged(viewer, moved, to);
                    notifications.linkedStatusChanged(viewer, moved, to);
                    return moved;
                }
                tries++;
                issue = (tries < STATUS_RETRIES ? issues.findByKey(viewer, issueKey)
                        : issues.lockByKey(viewer, issueKey)).orElseThrow(ApiException::notFound);
            }
        });
    }

    /**
     * Changes the title, the content, and the priority, whichever were sent (S3-AC-18). Staff edit any
     * issue they see; a customer only one they reported, while they belong to its company (S3-AC-20).
     *
     * <p>One transaction, retried once on a lock conflict: the issue row {@code FOR NO KEY UPDATE}
     * first, so the status and the version checked here are the ones the UPDATE changes (S3-AC-52). A
     * {@code CLOSED} issue is 400 whatever version was sent (S3-AC-21); then a version that is not the
     * issue's is 409 (S3-AC-19). Then the images of the edit are attached, before anything is inserted;
     * attaching does not raise the version (S5-AC-20). Values the issue already has change nothing, not
     * the version either (S3-AC-22). Each field that changed gets a history entry (S3-AC-62).
     */
    public IssueRow edit(Viewer viewer, String key, Edit input) {
        IssueKey issueKey = IssueKey.parse(key).orElseThrow(ApiException::notFound);
        return transactions.run(() -> {
            IssueRow issue = issues.lockByKey(viewer, issueKey).orElseThrow(ApiException::notFound);
            if (viewer.customer() && !reporters.writes(viewer, issue)) {
                throw ApiException.forbidden();
            }
            List<ApiError.FieldError> errors = new ArrayList<>();
            String title = input.title().sent() ? TextInput.required("title", input.title().value(), TITLE_MAX, errors)
                    : issue.title();
            String content = input.content().sent()
                    ? TextInput.multiLine("content", input.content().value(), false, CONTENT_MAX, errors)
                    : issue.content();
            Priority priority = issue.priority();
            if (input.priority().sent()) {
                if (input.priority().value() == null) {
                    errors.add(new ApiError.FieldError("priority", "REQUIRED"));
                } else {
                    priority = priority(input.priority().value(), errors);
                }
            }
            if (input.version() == null) {
                errors.add(new ApiError.FieldError("version", "REQUIRED"));
            }
            ImageIds images = ImageIds.of(input.imageIds(), errors);
            if (!errors.isEmpty()) {
                throw ApiException.invalid(errors);
            }
            if (issue.status() == IssueStatus.CLOSED) {
                throw closed();
            }
            if (input.version() != issue.version()) {
                throw ApiException.conflict("VERSION_CONFLICT", "Someone changed this issue. Read it again.");
            }
            boolean titleChanged = !title.equals(issue.title());
            boolean contentChanged = !content.equals(issue.content());
            boolean priorityChanged = priority != issue.priority();
            Instant now = now();
            attachments.attachImages(viewer.accountId(), issue.id(), null, false, false, images, now);
            if (!titleChanged && !contentChanged && !priorityChanged) {
                return issue;
            }
            issues.updateFields(issue.id(), title, content, priority, now);
            if (titleChanged) {
                history.insertChanged(issue.id(), Field.TITLE, issue.title(), title, viewer.accountId(), now);
            }
            if (contentChanged) {
                history.insertChanged(issue.id(), Field.CONTENT, issue.content(), content, viewer.accountId(), now);
            }
            if (priorityChanged) {
                history.insertChanged(issue.id(), Field.PRIORITY, issue.priority().name(), priority.name(),
                        viewer.accountId(), now);
            }
            return issues.findByKey(viewer, issueKey).orElseThrow();
        });
    }

    /**
     * Staff set the assignee of an issue they see, whatever its status (S3-AC-24, S3-AC-30): an
     * {@code ACTIVE} admin, or an {@code ACTIVE} agent assigned to the issue's company. Any other account
     * is the same 400, whatever the reason (S3-AC-25). The assignee the issue has changes nothing.
     */
    public IssueRow assign(Viewer viewer, String key, Long accountId) {
        return changeAssignee(viewer, key, issue -> {
            if (accountId == null) {
                throw ApiException.invalid("accountId", "REQUIRED");
            }
            return companies.findAssignable(viewer, issue.companyId(), accountId)
                    .orElseThrow(() -> ApiException.invalid("accountId", "UNKNOWN_VALUE")).accountId();
        });
    }

    /** Staff clear the assignee of an issue they see; an issue without one stays as it is (S3-AC-26). */
    public IssueRow unassign(Viewer viewer, String key) {
        return changeAssignee(viewer, key, issue -> null);
    }

    /**
     * A change of the assignee in one transaction, retried once on a lock conflict (slice 3 design
     * section 2): the issue row {@code FOR NO KEY UPDATE} first, in the caller's scope; then
     * {@code assignee} reads, in the same transaction, who it is to be; then the UPDATE, the history
     * entry, the webhook row, and the new assignee's inbox item and email (S3-AC-62, S3-AC-73,
     * S4-AC-31). No other row is locked. An assignee that does not change writes nothing (S3-AC-63).
     *
     * @param assignee given the locked issue, throws when the account cannot be its assignee; else the
     *     account ID, or null for nobody
     */
    private IssueRow changeAssignee(Viewer viewer, String key, Function<IssueRow, Long> assignee) {
        IssueKey issueKey = IssueKey.parse(key).orElseThrow(ApiException::notFound);
        return transactions.run(() -> {
            IssueRow issue = issues.lockByKey(viewer, issueKey).orElseThrow(ApiException::notFound);
            Long from = issue.assignee() == null ? null : issue.assignee().accountId();
            Long to = assignee.apply(issue);
            if (Objects.equals(from, to)) {
                return issue;
            }
            Instant now = now();
            issues.updateAssignee(issue.id(), to, now);
            history.insertChanged(issue.id(), Field.ASSIGNEE, from == null ? null : from.toString(),
                    to == null ? null : to.toString(), viewer.accountId(), now);
            webhooks.queue(WebhookKind.ASSIGNEE_CHANGED, issue.id(), null);
            if (to != null) {
                notifications.assigned(viewer, issue, to);
            }
            return issues.findByKey(viewer, issueKey).orElseThrow();
        });
    }

    /**
     * Staff put a label of the issue's company on an issue they see (LBL-2, S6A-AC-20). In one
     * transaction, retried once on a lock conflict (STATUS-7, slice 6a design section 0, 4): the issue
     * row {@code FOR NO KEY UPDATE} first, in the caller's scope, so a closing that came first is seen
     * here and one that comes later waits (S6A-AC-23); then the label under the issue's company, locked
     * so that its deletion waits for this one (S6A-AC-25). A label of another company and an ID nobody
     * has are one answer, which does not repeat the ID (S6A-AC-22). Then the status, then the limit of
     * 20, counted under the issue row (S6A-AC-24). A label the issue has already changes nothing
     * (S6A-AC-20); otherwise the version goes up by 1 and the history gets a {@code LABEL} entry with the
     * label's name (S6A-AC-60). Nobody is told (S6A-AC-63).
     */
    public IssueRow addLabel(Viewer viewer, String key, long labelId) {
        IssueKey issueKey = IssueKey.parse(key).orElseThrow(ApiException::notFound);
        return transactions.run(() -> {
            IssueRow issue = issues.lockByKey(viewer, issueKey).orElseThrow(ApiException::notFound);
            String name = labels.lockName(issue.companyId(), labelId)
                    .orElseThrow(() -> ApiException.invalid("id", "UNKNOWN_VALUE"));
            if (issue.status() == IssueStatus.CLOSED) {
                throw closed();
            }
            if (labels.onIssue(issue.id(), labelId)) {
                return issue;
            }
            if (labels.countOnIssue(issue.id()) >= ISSUE_LABEL_MAX) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ISSUE_LABEL_LIMIT_EXCEEDED",
                        "The issue has " + ISSUE_LABEL_MAX + " labels already.");
            }
            Instant now = now();
            if (!labels.insertOnIssue(issue.id(), issue.companyId(), labelId, now)) {
                throw new IllegalStateException("A locked label was not put on a locked issue.");
            }
            issues.updateVersion(issue.id(), now);
            history.insertChanged(issue.id(), Field.LABEL, null, name, viewer.accountId(), now);
            return issues.findByKey(viewer, issueKey).orElseThrow();
        });
    }

    /**
     * Staff take a label off an issue they see (LBL-2, S6A-AC-21), the issue row locked first as for
     * {@link #addLabel}. A label the issue does not have, a deleted one included, changes nothing; else
     * the version goes up by 1 and the history gets a {@code LABEL} entry with the label's name now.
     */
    public IssueRow removeLabel(Viewer viewer, String key, long labelId) {
        IssueKey issueKey = IssueKey.parse(key).orElseThrow(ApiException::notFound);
        return transactions.run(() -> {
            IssueRow issue = issues.lockByKey(viewer, issueKey).orElseThrow(ApiException::notFound);
            if (issue.status() == IssueStatus.CLOSED) {
                throw closed();
            }
            Optional<String> name = labels.deleteFromIssue(issue.id(), labelId);
            if (name.isEmpty()) {
                return issue;
            }
            Instant now = now();
            issues.updateVersion(issue.id(), now);
            history.insertChanged(issue.id(), Field.LABEL, name.get(), null, viewer.accountId(), now);
            return issues.findByKey(viewer, issueKey).orElseThrow();
        });
    }

    /**
     * Staff set the due date of an issue they see (DUE-1, S6A-AC-30). The date comes as the text sent
     * and is read strictly (S6A-AC-31): missing or empty is {@code REQUIRED}; anything but
     * {@code YYYY-MM-DD} of a day that exists, a time or a number among them, is {@code INVALID_FORMAT};
     * a date before today in the installation's time zone is {@code OUT_OF_RANGE} (S6A-AC-34), also the
     * date an overdue issue has already (S6A-AC-35). The date the issue has changes nothing (S6A-AC-36).
     */
    public IssueRow setDueDate(Viewer viewer, String key, String dueDate) {
        return changeDueDate(viewer, key, () -> {
            DueDates.Read read = DueDates.read(dueDate, zone.today(clock));
            if (read.error() != null) {
                throw ApiException.invalid("dueDate", read.error());
            }
            return read.date();
        });
    }

    /** Staff clear the due date of an issue they see; one without a due date stays as it is (S6A-AC-32). */
    public IssueRow clearDueDate(Viewer viewer, String key) {
        return changeDueDate(viewer, key, () -> null);
    }

    /**
     * A change of the due date in one transaction, retried once on a lock conflict: the issue row
     * {@code FOR NO KEY UPDATE} in the caller's scope (404), then {@code dueDate} checks the input (400),
     * then the status ({@code ISSUE_CLOSED}, S6A-AC-33). A changed date raises the version and gets a
     * {@code DUE_DATE} entry (S6A-AC-61); nobody is told (S6A-AC-63).
     *
     * @param dueDate throws when the input is not a date the issue can have; else the date, or null to clear
     */
    private IssueRow changeDueDate(Viewer viewer, String key, Supplier<LocalDate> dueDate) {
        IssueKey issueKey = IssueKey.parse(key).orElseThrow(ApiException::notFound);
        return transactions.run(() -> {
            IssueRow issue = issues.lockByKey(viewer, issueKey).orElseThrow(ApiException::notFound);
            LocalDate to = dueDate.get();
            if (issue.status() == IssueStatus.CLOSED) {
                throw closed();
            }
            if (Objects.equals(issue.dueDate(), to)) {
                return issue;
            }
            Instant now = now();
            issues.updateDueDate(issue.id(), to, now);
            history.insertChanged(issue.id(), Field.DUE_DATE, text(issue.dueDate()), text(to), viewer.accountId(),
                    now);
            return issues.findByKey(viewer, issueKey).orElseThrow();
        });
    }

    private static String text(LocalDate date) {
        return date == null ? null : date.toString();
    }

    /**
     * Whether the issue's due date is before today in the installation's time zone (S6A-AC-35). Judged
     * now, never stored; false without a due date.
     */
    public boolean overdue(IssueRow issue) {
        return DueDates.passed(issue.dueDate(), zone.today(clock));
    }

    /**
     * The labels of each of these issues, which the caller read in the viewer's scope, in name order
     * whatever the case (S6A-AC-50); one read for a whole page. An issue without labels is not in the map.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<IssueLabel>> labels(Collection<UUID> issueIds) {
        return labels.listOnIssues(issueIds);
    }

    /**
     * Everyone the viewer, who is staff, can mention in a comment on the issue (S4-AC-22): whoever can
     * be its assignee and, for a public comment, its reporter while {@code ACTIVE}; never the viewer.
     * The issue out of the viewer's scope is 404 before {@code internal} is read (S4-AC-23).
     *
     * @param internal {@code true} or {@code false} as the request had it; anything else is 400
     */
    @Transactional(readOnly = true)
    public List<StaffRow> mentionCandidates(Viewer viewer, String key, String internal) {
        IssueRow issue = get(viewer, key);
        if (!"true".equals(internal) && !"false".equals(internal)) {
            throw ApiException.invalid("internal", "INVALID_FORMAT");
        }
        return companies.listMentionable(viewer, issue.companyId(),
                "true".equals(internal) ? null : issue.reporterId());
    }

    /** One page of the history of an issue the viewer may see, oldest first; else 404 (S3-AC-64, S3-AC-67). */
    @Transactional(readOnly = true)
    public Paged<HistoryRow> history(Viewer viewer, String key, int page, int size) {
        IssueRow issue = get(viewer, key);
        PageRequest request = PageRequest.of(page, size);
        return request.of(history.list(viewer, issue.id(), request.offset(), request.size()),
                history.count(viewer, issue.id()));
    }

    /** Whether {@link #reopen} can work on the issue now, whoever asks (S3-AC-59). */
    public boolean reopenable(IssueRow issue) {
        return issue.reopenable(now());
    }

    /** The issue counts of every company the viewer may see, counted now (STATS-1 to STATS-3). */
    @Transactional(readOnly = true)
    public List<IssueRepository.CompanyCounts> companyStats(Viewer viewer) {
        return issues.countByCompany(viewer);
    }

    /** The issue with this key, if the viewer may see it; else 404 (COM-1). */
    @Transactional(readOnly = true)
    public IssueRow get(Viewer viewer, String key) {
        return IssueKey.parse(key).flatMap(k -> issues.findByKey(viewer, k)).orElseThrow(ApiException::notFound);
    }

    /**
     * One page of the issues the viewer may see that match the filters, newest first (S0-AC-47,
     * S3-AC-06, S3-AC-11). A value a filter cannot have is 400; a company or an account the viewer cannot see is
     * no error and gives an empty page, like one that does not exist (S3-AC-02, S3-AC-05). A request
     * with a search text is counted against the search limit first, whatever the text is (S3-AC-15).
     */
    @Transactional(readOnly = true)
    public Paged<IssueRow> list(Viewer viewer, Filters filters, int page, int size) {
        if (filters.q() != null) {
            rateLimit.count(AccountRateLimit.Action.SEARCH, viewer.accountId());
        }
        List<ApiError.FieldError> errors = new ArrayList<>();
        IssueFilter.Assignee assignee = filters.assignee() == null ? null
                : assignee(viewer, filters.assignee(), errors);
        String q = filters.q() == null ? null : searchText(filters.q(), errors);
        IssueRepository.Order order = order(filters.sort(), errors);
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors);
        }
        PageRequest request = PageRequest.of(page, size);
        IssueFilter filter = new IssueFilter(
                filters.statuses() == null ? List.of() : filters.statuses().stream().filter(Objects::nonNull).toList(),
                filters.companyCode(), assignee, filters.reporterId(), q,
                filters.labelIds() == null ? List.of()
                        : filters.labelIds().stream().filter(Objects::nonNull).distinct().toList());
        if (q == null) {
            return request.of(issues.list(viewer, filter, order, request.offset(), request.size()),
                    issues.count(viewer, filter));
        }
        IssueRepository.Found found = issues.search(viewer, filter, order, request.offset(), request.size());
        return request.of(found.rows(), found.total());
    }

    /**
     * The order of a list (S6A-AC-53): none or {@code createdAt} is newest first, {@code dueDate} the
     * earliest due date first. Any other value, in other case or with a direction, is
     * {@code UNKNOWN_VALUE} in {@code errors}.
     */
    private static IssueRepository.Order order(String sort, List<ApiError.FieldError> errors) {
        if (sort == null || sort.equals(SORT_CREATED_AT)) {
            return IssueRepository.Order.CREATED_AT;
        }
        if (sort.equals(SORT_DUE_DATE)) {
            return IssueRepository.Order.DUE_DATE;
        }
        errors.add(new ApiError.FieldError("sort", "UNKNOWN_VALUE"));
        return IssueRepository.Order.CREATED_AT;
    }

    /**
     * The search text of a list (S3-AC-14): a one-line value, so NFC and without the whitespace around
     * it, of 1 to {@link #SEARCH_MAX} code points. An empty one, also one of whitespace only, is
     * {@code TOO_SHORT} in {@code errors}.
     */
    private static String searchText(String received, List<ApiError.FieldError> errors) {
        if (!TextInput.storable(received)) {
            errors.add(new ApiError.FieldError("q", "INVALID_FORMAT"));
            return null;
        }
        String text = TextInput.oneLine(received);
        if (text.isEmpty()) {
            errors.add(new ApiError.FieldError("q", "TOO_SHORT"));
            return null;
        }
        if (TextInput.length(text) > SEARCH_MAX) {
            errors.add(new ApiError.FieldError("q", "TOO_LONG"));
            return null;
        }
        return text;
    }

    /**
     * The assignee filter of this value (S3-AC-03, S3-AC-04): {@code me} is the viewer, {@code none}
     * nobody, {@code unavailable} an assignee who can no longer see the issue, and a positive number an
     * account ID. Anything else is {@code UNKNOWN_VALUE} in {@code errors}.
     */
    private static IssueFilter.Assignee assignee(Viewer viewer, String value, List<ApiError.FieldError> errors) {
        switch (value) {
            case ASSIGNEE_ME:
                return new IssueFilter.Assignee(IssueFilter.Assignee.Kind.ACCOUNT, viewer.accountId());
            case ASSIGNEE_NONE:
                return new IssueFilter.Assignee(IssueFilter.Assignee.Kind.NONE, null);
            case ASSIGNEE_UNAVAILABLE:
                return new IssueFilter.Assignee(IssueFilter.Assignee.Kind.UNAVAILABLE, null);
            default:
        }
        if (ACCOUNT_ID.matcher(value).matches()) {
            return new IssueFilter.Assignee(IssueFilter.Assignee.Kind.ACCOUNT, Long.parseLong(value));
        }
        errors.add(new ApiError.FieldError("assignee", "UNKNOWN_VALUE"));
        return null;
    }

    /** The priority of this name; {@code NORMAL} with an entry in {@code errors} when there is none. */
    private static Priority priority(String name, List<ApiError.FieldError> errors) {
        try {
            return Priority.valueOf(name);
        } catch (IllegalArgumentException e) {
            errors.add(new ApiError.FieldError("priority", "UNKNOWN_VALUE"));
            return Priority.NORMAL;
        }
    }

    /** One answer for every account that cannot be the reporter of a filing by staff (S3-AC-33). */
    private static ApiError.FieldError unknownReporter() {
        return new ApiError.FieldError("reporterId", "UNKNOWN_VALUE");
    }

    private static ApiException closed() {
        return new ApiException(HttpStatus.BAD_REQUEST, "ISSUE_CLOSED", "This issue is closed.");
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
