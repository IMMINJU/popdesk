package io.popdesk.trash;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Concurrently;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.HeldWrite;
import io.popdesk.support.Wait;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A restore that waits for the row of a linked issue while another admin's delete of that issue is not committed yet
 * (S7A-AC-21, S7A-AC-12). The restore locks the issue and every issue linked to it in one statement; the delete of the
 * linked issue is kept waiting at its history INSERT ({@link HeldWrite} on {@code issue_history}, the rows of the
 * linked issue), after its UPDATE of that row and with the row's lock; the restore is sent and is let wait for the
 * row; then the delete goes on and commits.
 *
 * <p>The restore locks the rows with the account that deleted each joined to it. Under READ COMMITTED, PostgreSQL
 * reads the linked row again once the delete committed, now with who deleted it, but keeps the account row it joined
 * first, none: the ID of who deleted it is there and the name and role beside it are empty. A lock that reads those
 * columns that way answers 500. In either order the restore brings the issue back (S7A-AC-21: its links stay, a
 * link to an issue in the trash is hidden, S7A-AC-05), and the linked issue is in the trash.
 */
class RestoreDuringLinkedDeleteTest extends BareAppTest {

    @Autowired
    PostgreSQLContainer postgres;

    private IssueFlow flow;
    private TrashRows rows;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
        rows = new TrashRows(db);
    }

    @Test
    @DisplayName("S7A-AC-21 a restore that waits for the row of a linked issue while another admin deletes that issue brings the issue back once the delete commits, without a 5xx, and the linked issue is in the trash")
    void restoreWaitingForTheDeleteOfALinkedIssue() {
        TrashScene s = TrashScene.build(fixtures, flow, clock, "CONFIRMED");
        String linked = s.other().key();
        Session otherAdmin = fixtures.admin(s.admin());
        TrashSteps.delete(s.admin(), s.key());
        UUID linkedId = rows.id(linked);
        ApiResponse deleting;
        ApiResponse restoring;

        try (HeldWrite hold = new HeldWrite(db, postgres, "issue_history", "insert", linkedId.toString())) {
            CompletableFuture<ApiResponse> delete = CompletableFuture.supplyAsync(
                    () -> TrashSteps.deleteRequest(otherAdmin, linked).send());
            hold.awaitHeld("the history INSERT of the delete of the linked issue");
            CompletableFuture<ApiResponse> restore = CompletableFuture.supplyAsync(
                    () -> TrashSteps.restoreRequest(s.admin(), s.key()).send());
            Wait.until("the restore waits for a lock or finished",
                    () -> restore.isDone() || hold.waitingForALock() > 0 ? Boolean.TRUE : null);
            assertThat(restore.isDone()).as("the restore finished before the delete let the linked row go").isFalse();
            hold.release();
            deleting = Concurrently.finish(delete, "the delete of the linked issue");
            restoring = Concurrently.finish(restore, "the restore");
        }

        assertThat(deleting.status()).as("delete of the linked issue: " + deleting.snippet()).isEqualTo(204);
        assertThat(restoring.status()).as("restore after the delete of the linked issue: " + restoring.snippet())
                .isEqualTo(200);
        assertThat(restoring.json().path("key").asString(null)).as("key in the restore's answer").isEqualTo(s.key());
        assertThat(s.detail(flow).path("key").asString(null)).as("the restored issue, read again").isEqualTo(s.key());
        assertThat(TrashSteps.keys(s.admin())).as("keys of the trash").contains(linked).doesNotContain(s.key());
    }
}
