package io.popdesk.helpdesk.issue;

import io.popdesk.ApiError;
import io.popdesk.ApiException;
import io.popdesk.PageRequest;
import io.popdesk.Paged;
import io.popdesk.PatchText;
import io.popdesk.TextInput;
import io.popdesk.Transactions;
import io.popdesk.helpdesk.AccountRateLimit;
import io.popdesk.helpdesk.CompanyStatus;
import io.popdesk.helpdesk.IssueStatus;
import io.popdesk.helpdesk.Priority;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.company.CompanyRepository;
import io.popdesk.helpdesk.company.CompanyRepository.CompanyRow;
import io.popdesk.helpdesk.issue.IssueHistoryRepository.Field;
import io.popdesk.helpdesk.issue.IssueHistoryRepository.HistoryRow;
import io.popdesk.helpdesk.issue.IssueRepository.IssueRow;
import io.popdesk.notification.MailKind;
import io.popdesk.notification.MailQueue;
import io.popdesk.notification.WebhookKind;
import io.popdesk.notification.WebhookQueue;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Filing, listing, reading, and changing issues (ISSUE-1 to ISSUE-9, STATUS-1 to STATUS-7). Checks
 * follow conventions "Order of checks": the rate limit and sign-in first, then the target in the
 * caller's scope (404), then permission (403), then the input (400), then the state (400 or 409).
 *
 * <p>Filing is two calls so that attachments can be checked and written in between, outside the
 * filing transaction (architecture.md "Concurrency and lock order"): {@link #prepare} checks
 * everything and picks the issue ID, {@link #file} writes the issue.
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

    /** An account ID as the assignee filter gets it: a positive number that fits a {@code bigint}. */
    private static final Pattern ACCOUNT_ID = Pattern.compile("[1-9][0-9]{0,17}");

    /**
     * How often a status change that lost against another one reads the issue again and tries once
     * more before it takes the row lock, under which the next try cannot lose.
     */
    private static final int STATUS_RETRIES = 3;

    /** What a customer sent to file an issue, as received. {@code priority} is the name sent, or null. */
    public record NewIssue(String companyCode, String title, String content, String priority, Boolean privateIssue) {}

    /**
     * What staff sent to file an issue for a customer, as received (ISSUE-9): the fields of
     * {@link NewIssue}, the customer, and how the request came in.
     */
    public record NewIssueOnBehalf(NewIssue issue, Long reporterId, String channel) {}

    /**
     * A checked filing, not written yet. The ID is chosen now, so attachments can point at it first.
     * {@code createdBy} is the staff member who files it for the reporter; null when the reporter files it.
     */
    public record Draft(UUID id, long reporterId, Long createdBy, String channel, CompanyRow company, String title,
            String content, Priority priority, boolean privateIssue) {}

    /**
     * The filters of a list request, as received; a null one was not sent (ISSUE-5). {@code assignee}
     * is an account ID or one of the words {@code me}, {@code none}, {@code unavailable}; {@code q} is
     * the search text.
     */
    public record Filters(List<IssueStatus> statuses, String companyCode, String assignee, Long reporterId,
            String q) {}

    /**
     * What an edit sent (S3-AC-18). A field that was not sent keeps its value; {@code version} is the
     * one the caller read, or null when it was left out.
     */
    public record Edit(PatchText title, PatchText content, PatchText priority, Long version) {}

    private final IssueRepository issues;
    private final IssueHistoryRepository history;
    private final ReporterAccess reporters;
    private final CompanyRepository companies;
    private final WebhookQueue webhooks;
    private final MailQueue mail;
    private final AccountRateLimit rateLimit;
    private final Transactions transactions;
    private final Clock clock;

    IssueService(IssueRepository issues, IssueHistoryRepository history, ReporterAccess reporters,
            CompanyRepository companies, WebhookQueue webhooks, MailQueue mail, AccountRateLimit rateLimit,
            Transactions transactions, Clock clock) {
        this.issues = issues;
        this.history = history;
        this.reporters = reporters;
        this.companies = companies;
        this.webhooks = webhooks;
        this.mail = mail;
        this.rateLimit = rateLimit;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Checks a filing by a customer: counted against the rate limit (S0-AC-50), the company must be one
     * the customer belongs to (S0-AC-43, 404), every invalid field is listed (S0-AC-42), and the company
     * must be active. Nothing is written.
     */
    public Draft prepare(Viewer viewer, NewIssue input) {
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
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors);
        }
        if (company.status() != CompanyStatus.ACTIVE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "COMPANY_NOT_ACTIVE", "The company is not active.");
        }
        return new Draft(UUID.randomUUID(), viewer.accountId(), null, CHANNEL_PORTAL, company, title, content,
                priority, Boolean.TRUE.equals(input.privateIssue()));
    }

    /**
     * Checks a filing by staff for a customer (ISSUE-9), by the rules of {@link #prepare} and in the
     * same rate limit, counted before the company is looked up (S3-AC-39). The company must be one the
     * staff member sees (S3-AC-34, 404 whatever else is wrong). Every invalid field is listed, with them
     * a channel that is not one of {@link #CHANNELS_ON_BEHALF} (S3-AC-35) and a reporter who is not an
     * {@code ACTIVE} customer of the company, one answer whatever the reason (S3-AC-33). Then the
     * company must be active. Nothing is written; {@link #file} looks at the reporter again.
     */
    public Draft prepareOnBehalf(Viewer staff, NewIssueOnBehalf input) {
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
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors);
        }
        if (company.status() != CompanyStatus.ACTIVE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "COMPANY_NOT_ACTIVE", "The company is not active.");
        }
        return new Draft(UUID.randomUUID(), input.reporterId(), staff.accountId(), input.channel(), company, title,
                content, priority, Boolean.TRUE.equals(issue.privateIssue()));
    }

    /** Writes a prepared filing without attachments; see {@link #file(Viewer, Draft, Runnable)}. */
    public IssueRow file(Viewer viewer, Draft draft) {
        return file(viewer, draft, () -> {});
    }

    /**
     * Writes a prepared filing in one transaction, retried once on a lock conflict (S0-AC-85), in the
     * lock order of architecture.md: {@code storeAttachments} turns the filing's pending attachments
     * stored (step 2), then the webhook row, then the company counter, then the issue row with the number
     * it got (S0-AC-44), then the first entry of its history, by the person who filed (S3-AC-61). A failed
     * filing uses no number, and its attachments stay pending (S0-AC-63).
     *
     * <p>A filing by staff for a customer goes the same way (ISSUE-9). Before anything is written the
     * reporter is looked at again, in this transaction: a customer who was suspended or taken out of the
     * company since {@link #prepareOnBehalf} gets nothing filed in their name (S3-AC-33). It posts the
     * same webhook message (S3-AC-75), and the reporter's email is queued with the other new rows
     * (S3-AC-37). The staff member is the one the history names.
     */
    public IssueRow file(Viewer viewer, Draft draft, Runnable storeAttachments) {
        return transactions.run(() -> {
            Instant now = now();
            boolean onBehalf = draft.createdBy() != null;
            if (onBehalf && !companies.isReporter(viewer, draft.company().id(), draft.reporterId())) {
                throw ApiException.invalid(List.of(unknownReporter()));
            }
            storeAttachments.run();
            webhooks.queue(WebhookKind.ISSUE_CREATED, draft.id(), null);
            if (onBehalf) {
                mail.queue(MailKind.ISSUE_FILED_ON_BEHALF, draft.reporterId(), draft.id(), null);
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
     * tries the row is locked, which is still before any INSERT. The history entry and the webhook row
     * are written only after the UPDATE changed the row (S3-AC-51, S3-AC-63, S3-AC-73).
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
                    return issues.findByKey(viewer, issueKey).orElseThrow();
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
     * issue's is 409 (S3-AC-19). Values the issue already has change nothing, not the version either
     * (S3-AC-22). Each field that changed gets a history entry (S3-AC-62).
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
            if (!titleChanged && !contentChanged && !priorityChanged) {
                return issue;
            }
            Instant now = now();
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
     * entry, and the webhook row (S3-AC-62, S3-AC-73). No other row is locked. An assignee that does not
     * change writes nothing (S3-AC-63).
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
            return issues.findByKey(viewer, issueKey).orElseThrow();
        });
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

    /** The issue with this key, if the viewer may see it; else 404 (COM-1). */
    @Transactional(readOnly = true)
    public IssueRow get(Viewer viewer, String key) {
        return IssueKey.parse(key).flatMap(k -> issues.findByKey(viewer, k)).orElseThrow(ApiException::notFound);
    }

    /**
     * One page of the issues the viewer may see that match the filters, newest first (S0-AC-47,
     * S3-AC-06, S3-AC-11). A value a filter cannot have is 400; a company or an account the viewer cannot see is
     * no error and gives an empty page, like one that does not exist (S3-AC-02, S3-AC-05).
     */
    @Transactional(readOnly = true)
    public Paged<IssueRow> list(Viewer viewer, Filters filters, int page, int size) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        IssueFilter.Assignee assignee = filters.assignee() == null ? null
                : assignee(viewer, filters.assignee(), errors);
        String q = filters.q() == null ? null : searchText(filters.q(), errors);
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors);
        }
        PageRequest request = PageRequest.of(page, size);
        IssueFilter filter = new IssueFilter(
                filters.statuses() == null ? List.of() : filters.statuses().stream().filter(Objects::nonNull).toList(),
                filters.companyCode(), assignee, filters.reporterId(), q);
        if (q == null) {
            return request.of(issues.list(viewer, filter, request.offset(), request.size()),
                    issues.count(viewer, filter));
        }
        IssueRepository.Found found = issues.search(viewer, filter, request.offset(), request.size());
        return request.of(found.rows(), found.total());
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
