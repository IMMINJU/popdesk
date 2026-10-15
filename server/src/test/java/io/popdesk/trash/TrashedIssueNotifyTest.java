package io.popdesk.trash;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.link.LinkSteps;
import io.popdesk.link.LinkSteps.World;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Nothing is sent about an issue in the trash (S7A-AC-10, TRASH-2, TM-37, TM-49, acceptance 5절 13): the request that
 * deletes it deletes its rows in the send queue, those waiting for another attempt and those already
 * {@code failed}, so no webhook goes once the receiver works again (and none after a restore); and a linked issue
 * that is resolved gives no inbox item on the issue in the trash.
 *
 * <p>Two issues of one company get the same rows; one is deleted ({@code gone}), the other stays ({@code kept}) and
 * shows that the rows are there and are sent. The receiver answers 500 until the test lets it work. The queue rows
 * are read with {@link TrashRows} ({@code outbox}, by the issue).
 *
 * <p>A context of its own, shared with {@code notify.RetryTest}: the test leaves {@code failed} rows (and so operations
 * alerts) behind and moves the clock by a day. {@code POPDESK_TIMEZONE=UTC} is the default and only sets it apart.
 */
@TestPropertySource(properties = "POPDESK_TIMEZONE=UTC")
class TrashedIssueNotifyTest extends ConnectedAppTest {

    private IssueFlow flow;
    private TrashRows rows;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
        rows = new TrashRows(db);
    }

    /** A webhook text names an issue key followed by a space; so {@code CODE-1} does not match {@code CODE-12}. */
    private int webhooksOf(Issue issue) {
        return webhook().containing(issue.key() + " ").size();
    }

    private static boolean has(List<Map<String, Object>> queue, String state) {
        return queue.stream().anyMatch(r -> state.equals(r.get("state"))
                && ((Number) r.get("attempts")).longValue() >= 1);
    }

    @Test
    @DisplayName("S7A-AC-10 deleting an issue deletes its webhook rows in the send queue, the failed one and the one waiting for its next attempt, in the same request; once the receiver works no webhook about it goes, while the other issue's waiting row is sent; restoring it and moving the clock 11 minutes sends no webhook and no mail about it")
    void deleteDropsTheQueuedRows() {
        Session admin = fixtures.admin(flow.admin());
        Company company = fixtures.createCompany(admin);
        Session reporter = fixtures.customer(admin, company.code());
        webhook().respondWith(500);
        Issue gone = fixtures.fileIssue(reporter, company.code());
        Issue kept = fixtures.fileIssue(reporter, company.code());
        for (Issue issue : List.of(gone, kept)) {
            Wait.until("the first failed attempt of " + issue.key(), () -> has(rows.outbox(issue.key()), "queued")
                    ? Boolean.TRUE : null);
        }
        clock.advance(Duration.ofHours(24).plusSeconds(1));
        for (Issue issue : List.of(gone, kept)) {
            Wait.until("the row of " + issue.key() + " failed after 24 hours",
                    () -> has(rows.outbox(issue.key()), "failed") ? Boolean.TRUE : null);
        }
        Session customer = fixtures.signInAgain(reporter);
        for (Issue issue : List.of(gone, kept)) {
            fixtures.comment(customer, issue.key(), "Customer comment while the receiver fails");
            Wait.until("a failed and a waiting row of " + issue.key(), () -> {
                List<Map<String, Object>> queue = rows.outbox(issue.key());
                return has(queue, "failed") && has(queue, "queued") ? Boolean.TRUE : null;
            });
        }
        int goneSent = webhooksOf(gone);

        TrashSteps.delete(fixtures.signInAgain(admin), gone.key());
        List<Map<String, Object>> goneQueue = rows.outbox(gone.key());
        List<Map<String, Object>> keptQueue = rows.outbox(kept.key());
        webhook().respondWith(204);
        clock.advance(Duration.ofMinutes(10));

        assertThat(goneQueue).as("send queue rows of the deleted issue right after the delete").isEmpty();
        assertThat(has(keptQueue, "failed") && has(keptQueue, "queued"))
                .as("the other issue keeps its failed and its waiting row: " + keptQueue).isTrue();
        // The deleted issue's waiting row was queued before the other one's, so it would have gone first.
        Wait.until("the waiting row of " + kept.key() + " sent",
                () -> webhook().requests(r -> r.text().contains(kept.key() + " ") && r.text().contains("comment"))
                        .isEmpty() ? null : Boolean.TRUE);
        assertThat(webhooksOf(gone)).as("webhook requests about the deleted issue after the receiver works")
                .isEqualTo(goneSent);
        assertThat(rows.outbox(gone.key())).as("send queue rows of the deleted issue before the restore").isEmpty();

        // A delete that only hides the rows while the issue is in the trash sends them once it is restored.
        int goneMails = mailsAbout(gone);
        TrashSteps.restore(fixtures.signInAgain(admin), gone.key());
        clock.advance(Duration.ofMinutes(11));
        // A row queued after the restore and sent shows that the sending ran past any row of the deleted issue.
        Issue later = fixtures.fileIssue(customer, company.code());
        Wait.until("the webhook of " + later.key() + ", filed after the restore",
                () -> webhooksOf(later) > 0 ? Boolean.TRUE : null);
        assertThat(webhooksOf(gone)).as("webhook requests about the deleted issue 11 minutes after its restore")
                .isEqualTo(goneSent);
        assertThat(mailsAbout(gone)).as("mails about the deleted issue 11 minutes after its restore")
                .isEqualTo(goneMails);
        assertThat(rows.outbox(gone.key())).as("send queue rows of the restored issue at the end").isEmpty();
    }

    /** Mails whose subject names the issue, as {@code [KEY]}. */
    private int mailsAbout(Issue issue) {
        return (int) mail().messages().stream().filter(m -> m.subject().contains("[" + issue.key() + "]")).count();
    }

    @Test
    @DisplayName("S7A-AC-10 when an issue linked to an issue in the trash is resolved, the assignee of the issue in the trash gets no LINKED_ISSUE_RESOLVED item on it, while the assignee of a live linked issue gets theirs")
    void noLinkedItemOnTheTrash() {
        World w = LinkSteps.world(fixtures, flow);
        Session ofGone = fixtures.agent(w.admin(), w.a().code());
        Session ofKept = fixtures.agent(w.admin(), w.a().code());
        Issue hub = w.issueOfA(fixtures);
        Issue gone = w.issueOfA(fixtures);
        Issue kept = w.issueOfA(fixtures);
        flow.assign(w.admin(), gone.key(), ofGone.accountId());
        flow.assign(w.admin(), kept.key(), ofKept.accountId());
        LinkSteps.link(w.linker(), hub.key(), gone.key(), LinkSteps.RELATED);
        LinkSteps.link(w.linker(), hub.key(), kept.key(), LinkSteps.RELATED);
        TrashSteps.delete(w.admin(), gone.key());
        List<Map<String, Object>> itemsOfGone = rows.notifications(gone.key());

        flow.moveTo(w.linker(), hub.key(), "RESOLVED");

        Wait.until("the LINKED_ISSUE_RESOLVED item of the live linked issue",
                () -> TalkFlow.inboxOf(ofKept, kept.key()).stream()
                        .anyMatch(n -> LinkSteps.LINKED_RESOLVED.equals(TalkFlow.kind(n))) ? Boolean.TRUE : null);
        assertThat(rows.notifications(gone.key())).as("inbox rows about the issue in the trash after the resolve")
                .isEqualTo(itemsOfGone);
    }
}
