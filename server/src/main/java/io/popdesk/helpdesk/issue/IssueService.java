package io.popdesk.helpdesk.issue;

import io.popdesk.ApiError;
import io.popdesk.ApiException;
import io.popdesk.PageRequest;
import io.popdesk.Paged;
import io.popdesk.TextInput;
import io.popdesk.Transactions;
import io.popdesk.helpdesk.AccountRateLimit;
import io.popdesk.helpdesk.CompanyStatus;
import io.popdesk.helpdesk.Priority;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.company.CompanyRepository;
import io.popdesk.helpdesk.company.CompanyRepository.CompanyRow;
import io.popdesk.helpdesk.issue.IssueRepository.IssueRow;
import io.popdesk.notification.WebhookKind;
import io.popdesk.notification.WebhookQueue;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Filing, listing, and reading issues (ISSUE-1 to ISSUE-5). Checks follow conventions "Order of
 * checks": the rate limit and sign-in first, then the target in the caller's scope (404), then the
 * input (400), then the state (400).
 *
 * <p>Filing is two calls so that attachments can be checked and written in between, outside the
 * filing transaction (architecture.md "Concurrency and lock order"): {@link #prepare} checks
 * everything and picks the issue ID, {@link #file} writes the issue.
 */
@Service
public class IssueService {

    static final int TITLE_MAX = 200;
    static final int CONTENT_MAX = 10_000;
    static final String CHANNEL_PORTAL = "PORTAL";

    /** What a customer sent to file an issue, as received. {@code priority} is the name sent, or null. */
    public record NewIssue(String companyCode, String title, String content, String priority, Boolean privateIssue) {}

    /** A checked filing, not written yet. The ID is chosen now, so attachments can point at it first. */
    public record Draft(UUID id, long reporterId, CompanyRow company, String title, String content, Priority priority,
            boolean privateIssue) {}

    private final IssueRepository issues;
    private final CompanyRepository companies;
    private final WebhookQueue webhooks;
    private final AccountRateLimit rateLimit;
    private final Transactions transactions;
    private final Clock clock;

    IssueService(IssueRepository issues, CompanyRepository companies, WebhookQueue webhooks,
            AccountRateLimit rateLimit, Transactions transactions, Clock clock) {
        this.issues = issues;
        this.companies = companies;
        this.webhooks = webhooks;
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
        Priority priority = Priority.NORMAL;
        if (input.priority() != null) {
            try {
                priority = Priority.valueOf(input.priority());
            } catch (IllegalArgumentException e) {
                errors.add(new ApiError.FieldError("priority", "UNKNOWN_VALUE"));
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors);
        }
        if (company.status() != CompanyStatus.ACTIVE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "COMPANY_NOT_ACTIVE", "The company is not active.");
        }
        return new Draft(UUID.randomUUID(), viewer.accountId(), company, title, content, priority,
                Boolean.TRUE.equals(input.privateIssue()));
    }

    /** Writes a prepared filing without attachments; see {@link #file(Viewer, Draft, Runnable)}. */
    public IssueRow file(Viewer viewer, Draft draft) {
        return file(viewer, draft, () -> {});
    }

    /**
     * Writes a prepared filing in one transaction, retried once on a lock conflict (S0-AC-85), in the
     * lock order of architecture.md: {@code storeAttachments} turns the filing's pending attachments
     * stored (step 2), then the webhook row, then the company counter, then the issue row with the number
     * it got (S0-AC-44). A failed filing uses no number, and its attachments stay pending (S0-AC-63).
     */
    public IssueRow file(Viewer viewer, Draft draft, Runnable storeAttachments) {
        return transactions.run(() -> {
            Instant now = now();
            storeAttachments.run();
            webhooks.queue(WebhookKind.ISSUE_CREATED, draft.id(), null);
            int number = companies.updateNextIssueNumber(draft.company().id());
            issues.insert(draft.id(), draft.company().id(), number, draft.title(), draft.content(), draft.priority(),
                    draft.privateIssue(), draft.reporterId(), CHANNEL_PORTAL, now);
            return issues.findByKey(viewer, new IssueKey(draft.company().code(), number)).orElseThrow();
        });
    }

    /** The issue with this key, if the viewer may see it; else 404 (COM-1). */
    @Transactional(readOnly = true)
    public IssueRow get(Viewer viewer, String key) {
        return IssueKey.parse(key).flatMap(k -> issues.findByKey(viewer, k)).orElseThrow(ApiException::notFound);
    }

    /** One page of the issues the viewer may see, newest first (S0-AC-47). */
    @Transactional(readOnly = true)
    public Paged<IssueRow> list(Viewer viewer, int page, int size) {
        PageRequest request = PageRequest.of(page, size);
        return request.of(issues.list(viewer, request.offset(), request.size()), issues.count(viewer));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
