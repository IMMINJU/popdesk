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
import io.popdesk.helpdesk.issue.IssueKey;
import io.popdesk.helpdesk.issue.IssueRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Comments on issues (CMT-1, CMT-2). Checks follow conventions "Order of checks": the rate limit, the
 * issue in the caller's scope (404), permission (403), input (400), then the issue's state (400).
 */
@Service
public class CommentService {

    static final int CONTENT_MAX = 4_000;

    private final CommentRepository comments;
    private final IssueRepository issues;
    private final CompanyRepository companies;
    private final MailQueue mail;
    private final WebhookQueue webhooks;
    private final AccountRateLimit rateLimit;
    private final Transactions transactions;
    private final Clock clock;

    CommentService(CommentRepository comments, IssueRepository issues, CompanyRepository companies, MailQueue mail,
            WebhookQueue webhooks, AccountRateLimit rateLimit, Transactions transactions, Clock clock) {
        this.comments = comments;
        this.issues = issues;
        this.companies = companies;
        this.mail = mail;
        this.webhooks = webhooks;
        this.rateLimit = rateLimit;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Adds a comment. Customers add public comments to issues they reported while they still belong to
     * the company (S0-AC-52, PROJ-4); staff add public or internal comments to issues they can see.
     *
     * <p>One transaction, retried once on a lock conflict, in the lock order of architecture.md: the
     * issue row {@code FOR NO KEY UPDATE} before any INSERT (S0-AC-51), then the comment and the outbox
     * rows. A public staff comment sets the first response time once (S0-AC-46) and emails the reporter
     * (S0-AC-70); an internal one does neither (S0-AC-53). A customer's comment goes to the webhook.
     */
    public CommentRow add(Viewer viewer, String key, String content, boolean internal) {
        rateLimit.count(AccountRateLimit.Action.COMMENT, viewer.accountId());
        IssueKey issueKey = IssueKey.parse(key).orElseThrow(ApiException::notFound);
        return transactions.run(() -> {
            IssueRow issue = issues.lockByKey(viewer, issueKey).orElseThrow(ApiException::notFound);
            if (viewer.customer() && (internal || issue.reporterId() != viewer.accountId()
                    || companies.findByCode(viewer, issue.companyCode()).isEmpty())) {
                throw ApiException.forbidden();
            }
            List<ApiError.FieldError> errors = new ArrayList<>();
            String text = TextInput.multiLine("content", content, true, CONTENT_MAX, errors);
            if (!errors.isEmpty()) {
                throw ApiException.invalid(errors);
            }
            if (issue.status() == IssueStatus.CLOSED) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ISSUE_CLOSED", "This issue is closed.");
            }
            Instant now = now();
            long id = comments.insert(issue.id(), viewer.accountId(), internal, text, now);
            if (viewer.customer()) {
                webhooks.queue(WebhookKind.CUSTOMER_COMMENT, issue.id(), id);
            } else if (!internal) {
                mail.queue(MailKind.STAFF_COMMENT, issue.reporterId(), issue.id(), id);
                issues.updateFirstResponse(issue.id(), now);
            }
            return comments.find(viewer, issue.id(), id).orElseThrow();
        });
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

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
