package io.popdesk.helpdesk.link;

import io.popdesk.ApiException;
import io.popdesk.Transactions;
import io.popdesk.helpdesk.AccountRateLimit;
import io.popdesk.helpdesk.IssueStatus;
import io.popdesk.helpdesk.LinkType;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.issue.IssueHistoryRepository;
import io.popdesk.helpdesk.issue.IssueHistoryRepository.Field;
import io.popdesk.helpdesk.issue.IssueKey;
import io.popdesk.helpdesk.issue.IssueRepository;
import io.popdesk.helpdesk.issue.IssueRepository.IssueRow;
import io.popdesk.helpdesk.issue.LinkHistoryValue;
import io.popdesk.helpdesk.link.LinkRepository.LinkRow;
import io.popdesk.helpdesk.link.LinkRepository.Stored;
import io.popdesk.helpdesk.link.LinkRules.Decision;
import io.popdesk.helpdesk.link.LinkRules.Link;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Links between two issues (LINK-1 to LINK-7), for staff. Checks follow S6C-AC-14: the rate limit before
 * anything else, then this issue (404), the input and the other issue, the same issue, and under the lock of
 * both issue rows the status of this issue, a link already there, a duplicate chain, and the limits.
 *
 * <p>Linking and removing a link is one transaction, retried once on a lock conflict, that locks both
 * issue rows {@code FOR NO KEY UPDATE} in one statement in the order of their IDs (slice 6c design section
 * 0, 1, architecture.md "Concurrency and lock order"), with the caller's scope in it, before it reads the
 * links of either: only these two writes change links, and both hold both locks, so the counts and the
 * chain checks hold until the commit (S6C-AC-08). Neither issue's version or status changes (S6C-AC-23,
 * S6C-AC-30); both get a {@code LINK} history entry with the other issue's ID only (S6C-AC-20). Not audited.
 */
@Service
public class LinkService {

    /** A link of an issue as the caller reads it, its type seen from that issue (S6C-AC-13). */
    public record LinkView(LinkType type, LinkRow row) {}

    private final LinkRepository links;
    private final IssueRepository issues;
    private final IssueHistoryRepository history;
    private final AccountRateLimit rateLimit;
    private final Transactions transactions;
    private final Clock clock;

    LinkService(LinkRepository links, IssueRepository issues, IssueHistoryRepository history,
            AccountRateLimit rateLimit, Transactions transactions, Clock clock) {
        this.links = links;
        this.issues = issues;
        this.history = history;
        this.rateLimit = rateLimit;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * The links of an issue the viewer sees whose other issue the viewer sees too, in the order they were
     * made (S6C-AC-12). Also on a {@code CLOSED} issue.
     */
    @Transactional(readOnly = true)
    public List<LinkView> list(Viewer viewer, String key) {
        IssueRow issue = IssueKey.parse(key).flatMap(k -> issues.findByKey(viewer, k))
                .orElseThrow(ApiException::notFound);
        return links.listVisible(viewer, issue.id(), null).stream()
                .map(row -> new LinkView(row.link().seenFrom(issue.id()), row)).toList();
    }

    /**
     * Staff link the issue {@code key} to the issue {@code otherKey} (S6C-AC-01). Both are read as the
     * request had them: a missing or blank {@code otherKey} is {@code REQUIRED}, one that is no key or
     * names an issue the caller cannot see is 404 (S6C-AC-02, S6C-AC-03); {@code type} is one of the three
     * names or 400.
     */
    public LinkView create(Viewer viewer, String key, String otherKey, String type) {
        rateLimit.count(AccountRateLimit.Action.LINK, viewer.accountId());
        return transactions.run(() -> {
            IssueRow issue = IssueKey.parse(key).flatMap(k -> issues.findByKey(viewer, k))
                    .orElseThrow(ApiException::notFound);
            if (otherKey == null || otherKey.isBlank()) {
                throw ApiException.invalid("issueKey", "REQUIRED");
            }
            IssueRow other = IssueKey.parse(otherKey.strip()).flatMap(k -> issues.findByKey(viewer, k))
                    .orElseThrow(ApiException::notFound);
            LinkType wanted = type(type);
            if (issue.id().equals(other.id())) {
                throw refused(LinkRules.TO_SELF, "An issue cannot be linked to itself.");
            }
            IssueRow locked = lockPair(viewer, issue, other);
            if (locked.status() == IssueStatus.CLOSED) {
                throw issueClosed();
            }
            Decision decision = LinkRules.decide(issue.id(), other.id(), wanted, links.linksOf(issue.id()),
                    links.linksOf(other.id()));
            if (decision.errorCode() != null) {
                throw refusal(decision.errorCode());
            }
            Instant now = now();
            long id = links.insert(decision.link(), viewer.accountId(), now);
            LinkType seen = decision.link().seenFrom(issue.id());
            history.insertChanged(issue.id(), Field.LINK, null, LinkHistoryValue.of(seen, other.id()),
                    viewer.accountId(), now);
            history.insertChanged(other.id(), Field.LINK, null, LinkHistoryValue.ofOtherSide(seen, issue.id()),
                    viewer.accountId(), now);
            return new LinkView(seen, links.listVisible(viewer, issue.id(), id).getFirst());
        });
    }

    /**
     * Staff remove the link between the issue {@code key} and the issue {@code otherKey}, from either side
     * (S6C-AC-10). An issue the caller cannot see, and two issues without a link between them, are 404
     * (S6C-AC-11); then a {@code CLOSED} issue in the path is 400 (S6C-AC-14).
     */
    public void delete(Viewer viewer, String key, String otherKey) {
        rateLimit.count(AccountRateLimit.Action.LINK, viewer.accountId());
        transactions.run(() -> {
            IssueRow issue = IssueKey.parse(key).flatMap(k -> issues.findByKey(viewer, k))
                    .orElseThrow(ApiException::notFound);
            IssueRow other = IssueKey.parse(otherKey).flatMap(k -> issues.findByKey(viewer, k))
                    .orElseThrow(ApiException::notFound);
            if (issue.id().equals(other.id())) {
                throw ApiException.notFound();
            }
            IssueRow locked = lockPair(viewer, issue, other);
            Stored stored = links.between(issue.id(), other.id()).orElseThrow(ApiException::notFound);
            if (locked.status() == IssueStatus.CLOSED) {
                throw issueClosed();
            }
            Instant now = now();
            links.delete(stored.id());
            LinkType seen = stored.link().seenFrom(issue.id());
            history.insertChanged(issue.id(), Field.LINK, LinkHistoryValue.of(seen, other.id()), null,
                    viewer.accountId(), now);
            history.insertChanged(other.id(), Field.LINK, LinkHistoryValue.ofOtherSide(seen, issue.id()), null,
                    viewer.accountId(), now);
        });
    }

    /**
     * Locks both issue rows in the order of their IDs, in the viewer's scope (slice 6c design section 0, 1);
     * 404 when either is gone from it meanwhile.
     *
     * @return {@code issue} as it is under the lock
     */
    private IssueRow lockPair(Viewer viewer, IssueRow issue, IssueRow other) {
        List<IssueRow> locked = issues.lockPair(viewer, issue.id(), other.id());
        if (locked.size() != 2) {
            throw ApiException.notFound();
        }
        return locked.stream().filter(r -> r.id().equals(issue.id())).findFirst().orElseThrow();
    }

    /** The type as the request had it: null is {@code REQUIRED}, anything but the three names {@code UNKNOWN_VALUE}. */
    private static LinkType type(String type) {
        if (type == null) {
            throw ApiException.invalid("type", "REQUIRED");
        }
        for (LinkType each : LinkType.values()) {
            if (each.name().equals(type)) {
                return each;
            }
        }
        throw ApiException.invalid("type", "UNKNOWN_VALUE");
    }

    private static ApiException refusal(String code) {
        return switch (code) {
            case LinkRules.EXISTS -> new ApiException(HttpStatus.CONFLICT, code, "The two issues are linked already.");
            case LinkRules.DUPLICATE_CHAIN -> refused(code, "This link would make a chain of duplicates.");
            case LinkRules.LIMIT_EXCEEDED -> refused(code, "An issue has as many links as it can have.");
            default -> refused(code, "An issue cannot be linked to itself.");
        };
    }

    private static ApiException refused(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static ApiException issueClosed() {
        return new ApiException(HttpStatus.BAD_REQUEST, "ISSUE_CLOSED", "This issue is closed.");
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
