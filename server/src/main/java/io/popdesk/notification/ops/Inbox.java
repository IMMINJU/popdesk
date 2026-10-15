package io.popdesk.notification.ops;

import io.popdesk.ApiException;
import io.popdesk.PageRequest;
import io.popdesk.Paged;
import io.popdesk.notification.InboxFacts;
import io.popdesk.notification.InboxFacts.Actor;
import io.popdesk.notification.InboxFacts.Reader;
import io.popdesk.notification.IssueFacts.Issue;
import io.popdesk.notification.IssueInbox;
import io.popdesk.notification.ops.OpsRepository.InboxRow;
import io.popdesk.notification.ops.OpsRepository.Shown;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
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
 * The caller's inbox on either app (INBOX-3): only their own items, and of the items about an issue
 * only those whose issue they can see now (S4-AC-42). The item stays in the table meanwhile, so it is
 * back when they see the issue again.
 *
 * <p>Notification does not read helpdesk's tables. A read collects the issues the account's items are
 * about, asks helpdesk which of them the account sees ({@link InboxFacts#visible}), and puts the
 * answer in the query, so the page, the total, and the unread count all leave out the same items.
 */
@Service
public class Inbox {

    /**
     * The kinds a customer's inbox lists (S4-AC-46). A customer's account gets no item of another kind;
     * the condition is in the query all the same, so that one never shows on the portal.
     */
    static final Set<String> CUSTOMER_KINDS = Set.of(IssueInbox.Kind.STAFF_COMMENT.name(),
            IssueInbox.Kind.ISSUE_RESOLVED.name(), IssueInbox.Kind.ISSUE_CLOSED.name(),
            IssueInbox.Kind.ISSUE_REOPENED.name(), IssueInbox.Kind.ISSUE_FILED_ON_BEHALF.name(),
            IssueInbox.Kind.MENTIONED.name());

    /**
     * An item as it is read. {@code issue} is the issue as it is now and {@code actor} who did it, as
     * the reader may see them; both null for an item about no issue. {@code companyCode} is the issue's
     * company, or the company a membership request is about.
     */
    public record Item(long id, String kind, Issue issue, String companyCode, Long commentId, Actor actor,
            Instant createdAt, Instant readAt, Task task) {}

    /** The task an item is about, with its title as it is now (S6B-AC-33). */
    public record Task(long id, String title) {}

    private final OpsRepository ops;
    private final InboxFacts facts;
    private final Clock clock;

    Inbox(OpsRepository ops, InboxFacts facts, Clock clock) {
        this.ops = ops;
        this.facts = facts;
        this.clock = clock;
    }

    /** One page of the account's items, newest first (S4-AC-39, S4-AC-40). */
    @Transactional(readOnly = true)
    public Paged<Item> list(long accountId, Reader reader, int page, int size) {
        PageRequest request = PageRequest.of(page, size);
        Shown shown = shown(accountId, reader, false);
        List<InboxRow> rows = ops.listInbox(shown, request.offset(), request.size());
        Map<UUID, Issue> issues = facts.issues(distinct(rows, InboxRow::issueId));
        Map<Long, Actor> actors = facts.actors(distinct(rows, InboxRow::actorId), reader);
        Map<Long, String> tasks = reader == Reader.STAFF ? facts.taskTitles(distinct(rows, InboxRow::taskId)) : Map.of();
        return request.map(rows, ops.countInbox(shown), row -> item(row, issues, actors, tasks));
    }

    /** How many of the items {@link #list} would list are unread (S4-AC-43). */
    @Transactional(readOnly = true)
    public long unread(long accountId, Reader reader) {
        return ops.countUnread(shown(accountId, reader, true));
    }

    /** Marks the item read; 404 when it is not the caller's. Marking it again keeps the first time. */
    @Transactional
    public void markRead(long accountId, long id) {
        if (!ops.updateRead(id, accountId, now())) {
            throw ApiException.notFound();
        }
    }

    /**
     * Marks every unread item of the caller read, also those about issues the caller cannot see now, so
     * they are not unread when they are back (S4-AC-45).
     */
    @Transactional
    public void markAllRead(long accountId) {
        ops.updateAllRead(accountId, now());
    }

    /**
     * What the reader's inbox shows now: for a customer only {@link #CUSTOMER_KINDS}, and the issues the
     * account sees among those its items are about; with {@code unreadOnly}, among those of its unread
     * items, which is all a count of them needs.
     */
    private Shown shown(long accountId, Reader reader, boolean unreadOnly) {
        Collection<String> kinds = reader == Reader.CUSTOMER ? CUSTOMER_KINDS : null;
        List<UUID> about = ops.listInboxIssues(accountId, kinds, unreadOnly);
        return new Shown(accountId, kinds, about.isEmpty() ? Set.of() : facts.visible(accountId, about));
    }

    private static Item item(InboxRow row, Map<UUID, Issue> issues, Map<Long, Actor> actors,
            Map<Long, String> tasks) {
        Issue issue = row.issueId() == null ? null : issues.get(row.issueId());
        String taskTitle = row.taskId() == null ? null : tasks.get(row.taskId());
        return new Item(row.id(), row.kind(), issue, issue == null ? row.companyCode() : issue.companyCode(),
                row.commentId(), row.actorId() == null ? null : actors.get(row.actorId()), row.createdAt(),
                row.readAt(), taskTitle == null ? null : new Task(row.taskId(), taskTitle));
    }

    private static <T> Set<T> distinct(List<InboxRow> rows, Function<InboxRow, T> value) {
        return rows.stream().map(value).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
