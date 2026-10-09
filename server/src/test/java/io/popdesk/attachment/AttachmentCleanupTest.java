package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.bytes;
import static io.popdesk.attachment.AttachmentSteps.download;
import static io.popdesk.attachment.AttachmentSteps.list;
import static io.popdesk.attachment.AttachmentSteps.text;
import static io.popdesk.attachment.AttachmentSteps.upload;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.AttachmentRows.Row;
import io.popdesk.attachment.AttachmentSteps.Scene;
import io.popdesk.attachment.StuckAttachments.Stuck;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.DbInspector;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * What a failed filing leaves behind, and the daily attachment cleanup.
 *
 * <p>The cleanup wakes on a short real-time period and runs when the app's {@code Clock} says a day
 * has passed since its last run (tasks.md section 6), so a test moves the clock 25 hours and waits.
 * Moving the clock that far ends every session, so the tests sign in again afterwards. Failures are
 * put in with {@code test_} triggers ({@link FailureTriggers}), dropped before each test ends; the
 * races hold one step of an upload in such a trigger until the test releases it ({@link Hold}).
 */
class AttachmentCleanupTest extends BareAppTest {

    private static final Duration PAST_A_DAY = Duration.ofHours(25);

    /** The cleanup wakes every few seconds of real time; allow a few periods. */
    private static final Duration CLEANUP = Duration.ofSeconds(30);

    @Autowired
    PostgreSQLContainer postgres;

    private AttachmentSteps steps;
    private AttachmentRows rows;
    private StuckAttachments stuck;

    @BeforeEach
    void setUp() {
        steps = new AttachmentSteps(fixtures);
        rows = new AttachmentRows(db);
        stuck = new StuckAttachments(db);
    }

    // S0-AC-63

    @Test
    @DisplayName("S0-AC-63 a filing rejected for its title files nothing and makes no attachment row")
    void filingWithBadTitle() {
        Scene s = steps.scene();
        String name = Unique.text("bad-title") + ".txt";

        ApiResponse rejected = fileWithFiles(s, "", Map.of(name, bytes(50, 1)));

        assertThat(rejected.status()).isEqualTo(400);
        assertThat(rejected.errorCode()).isEqualTo("INVALID_INPUT");
        assertNoIssue(s);
        assertThat(rows.byFileName(name)).as("attachment rows for %s", name).isEmpty();
    }

    @Test
    @DisplayName("S0-AC-63 a filing rejected for an empty second file files nothing, makes no attachment row, and uses no number")
    void filingWithEmptySecondFile() {
        Scene s = steps.scene();
        String name = Unique.text("first-ok") + ".txt";
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(name, bytes(50, 2));
        files.put("empty.txt", new byte[0]);

        ApiResponse rejected = fileWithFiles(s, Unique.text("Two files"), files);

        assertThat(rejected.status()).isEqualTo(400);
        assertThat(rejected.errorCode()).isEqualTo("FILE_EMPTY");
        assertNoIssue(s);
        assertThat(rows.byFileName(name)).as("attachment rows for %s", name).isEmpty();
        assertThat(fixtures.fileIssue(s.customer(), s.code()).key()).isEqualTo(s.code() + "-1");
    }

    @Test
    @DisplayName("S0-AC-63 a filing that fails right before taking the number uses no number, and its file stays pending and unseen")
    void failureBeforeTheNumber() {
        Scene s = steps.scene();
        String companyTable = rows.tableHolding(s.company().legalName());
        String name = Unique.text("before-number") + ".txt";

        ApiResponse failed;
        try (FailureTriggers triggers = new FailureTriggers(db)) {
            triggers.failNumberOf(companyTable, s.code());
            failed = fileWithFiles(s, Unique.text("Fails at the number"), Map.of(name, bytes(50, 3)));
        }

        assertThat(failed.status()).isEqualTo(500);
        assertThat(failed.errorCode()).isEqualTo("INTERNAL_ERROR");
        assertNoIssue(s);
        assertThat(rows.byFileName(name)).extracting(Row::state).containsExactly("pending");
        Issue next = fixtures.fileIssue(s.customer(), s.code());
        assertThat(next.key()).isEqualTo(s.code() + "-1");
        assertThat(list(s.customer(), next.key())).isEmpty();
    }

    @Test
    @DisplayName("S0-AC-63 when the cleanup comes for a pending row while step ③ holds it, step ③ wins and the file stays stored")
    void cleanupRacesStepThree() throws Exception {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        String table = stuck.attachmentTable(s.customer(), issue.key());
        String name = Unique.text("race") + ".bin";
        byte[] content = bytes(1_000, Unique.number());
        ApiResponse created;
        try (FailureTriggers triggers = new FailureTriggers(db); Hold hold = new Hold(db, postgres)) {
            triggers.holdStoring(table, name, hold);
            CompletableFuture<ApiResponse> running =
                    CompletableFuture.supplyAsync(() -> upload(s.customer(), issue.key(), name, content));
            int stepThree = Hold.awaitHeld(db, "step ③ of the upload");
            Instant due = clock.advance(PAST_A_DAY);
            Wait.until("the cleanup at the held row: waiting for the lock of step ③, or a whole run done", CLEANUP,
                    () -> blockedBy(stepThree) || cleanupRanAtOrAfter(due) ? true : null);
            hold.release();
            created = finish(running);
        }

        assertThat(created.status()).isEqualTo(201);
        String id = created.jsonText("id");
        Wait.holds("the attachment stays stored while the cleanup runs", Duration.ofSeconds(5),
                () -> rows.byId(id).state().equals("stored") && rows.contentCopies(content) == 1);
        Session again = fixtures.signInAgain(s.customer());
        assertThat(download(again, issue.key(), id).body()).isEqualTo(content);
    }

    @Test
    @DisplayName("S0-AC-63 when the cleanup ends a pending row while step ② is still writing its file, the cleanup wins: the upload fails and the file is never stored or shown")
    void cleanupWinsOverUnfinishedUpload() throws Exception {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        String name = Unique.text("cleaned-first") + ".bin";
        byte[] content = bytes(1_000, Unique.number());
        ApiResponse finished;
        String id;
        try (FailureTriggers triggers = new FailureTriggers(db); Hold hold = new Hold(db, postgres)) {
            triggers.holdWritingContent(content, hold);
            CompletableFuture<ApiResponse> running =
                    CompletableFuture.supplyAsync(() -> upload(s.customer(), issue.key(), name, content));
            Hold.awaitHeld(db, "step ② of the upload");
            List<Row> pending = rows.byFileName(name);
            assertThat(pending).as("rows for %s while step ② is held", name).extracting(Row::state)
                    .containsExactly("pending");
            id = pending.getFirst().id();
            clock.advance(PAST_A_DAY);
            Wait.untilAsserted("the cleanup ended the pending row while step ② is held", CLEANUP,
                    () -> assertThat(rows.findById(id).map(Row::state)).satisfiesAnyOf(
                            state -> assertThat(state).isEmpty(),
                            state -> assertThat(state).contains("purged")));
            hold.release();
            finished = finish(running);
        }

        assertThat(finished.status()).as("the upload after the cleanup ended its row: %s", finished)
                .isGreaterThanOrEqualTo(400);
        Wait.holds("the attachment is not stored after the upload ended", Duration.ofSeconds(3),
                () -> rows.findById(id).map(Row::state).filter(state -> state.equals("stored")).isEmpty());
        Session again = fixtures.signInAgain(s.customer());
        assertThat(list(again, issue.key())).extracting(n -> text(n, "id")).doesNotContain(id);
        ApiResponse fetched = download(again, issue.key(), id);
        assertThat(fetched.status()).isEqualTo(404);
        assertThat(fetched.errorCode()).isEqualTo("NOT_FOUND");
    }

    /** Whether some server process waits for a lock that the process {@code pid} holds. */
    private boolean blockedBy(int pid) {
        return db.count("select count(*) from pg_stat_activity where ? = any(pg_blocking_pids(pid))", pid) > 0;
    }

    /**
     * Whether the cleanup record of {@code attachment-cleanup} (S0-AC-82, tasks.md 6절) holds a time
     * at or after {@code at}: a run that started after the clock moved has ended. The record is found
     * in any text column of any table.
     */
    private boolean cleanupRanAtOrAfter(Instant at) {
        for (String table : db.tables()) {
            for (DbInspector.Column c : db.columns(table)) {
                if (!c.dataType().equals("text") && !c.dataType().equals("character varying")) {
                    continue;
                }
                for (Map<String, Object> row : db.rows("select * from " + AttachmentRows.quote(table) + " where "
                        + AttachmentRows.quote(c.name()) + " = 'attachment-cleanup'")) {
                    for (Object value : row.values()) {
                        Instant time = value instanceof Timestamp ts ? ts.toInstant()
                                : value instanceof OffsetDateTime odt ? odt.toInstant() : null;
                        if (time != null && !time.isBefore(at)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    // S0-AC-62

    @Test
    @DisplayName("S0-AC-62 the cleanup removes the file of a pending row over an hour old and ends the row; a pending row under an hour stays")
    void cleanupEndsOldPendingRows() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        Stuck old = stuck.pending(s.customer(), issue.key());
        assertThat(rows.contentCopies(old.content())).as("content of the old pending row").isEqualTo(1);

        clock.advance(PAST_A_DAY);
        Session again = fixtures.signInAgain(s.customer());
        Stuck young = stuck.pending(again, issue.key());

        Wait.untilAsserted("the old pending row cleaned up", CLEANUP, () -> {
            assertThat(rows.contentCopies(old.content())).isZero();
            assertThat(rows.findById(old.id()).map(Row::state)).satisfiesAnyOf(
                    state -> assertThat(state).isEmpty(),
                    state -> assertThat(state).contains("purged"));
        });
        assertThat(rows.byId(young.id()).state()).isEqualTo("pending");
        assertThat(rows.contentCopies(young.content())).isEqualTo(1);
    }

    @Test
    @DisplayName("S0-AC-62 the cleanup ends a pending row whose file was never written, taking the missing file as removed")
    void missingFileCountsAsRemoved() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        String name = Unique.text("never-written") + ".bin";
        byte[] content = bytes(1_000, Unique.number());
        ApiResponse failed;
        try (FailureTriggers triggers = new FailureTriggers(db)) {
            triggers.failWritingContent(content);
            failed = upload(s.customer(), issue.key(), name, content);
        }
        assertThat(failed.status()).isEqualTo(500);
        List<Row> left = rows.byFileName(name);
        assertThat(left).extracting(Row::state).containsExactly("pending");
        assertThat(rows.contentCopies(content)).isZero();
        String id = left.getFirst().id();

        clock.advance(PAST_A_DAY);

        Wait.untilAsserted("the pending row without a file cleaned up", CLEANUP,
                () -> assertThat(rows.findById(id).map(Row::state)).satisfiesAnyOf(
                        state -> assertThat(state).isEmpty(),
                        state -> assertThat(state).contains("purged")));
    }

    @Test
    @DisplayName("S0-AC-62 the cleanup removes the file of a purging row and marks it purged; stored attachments over an hour old stay")
    void cleanupEndsPurgingRows() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        Stuck purging = stuck.purging(s.customer(), issue.key());
        byte[] kept = bytes(1_000, Unique.number());
        String keptId = upload(s.customer(), issue.key(), "kept.bin", kept).expectStatus(201).jsonText("id");

        clock.advance(PAST_A_DAY);

        Wait.untilAsserted("the purging row cleaned up", CLEANUP, () -> {
            assertThat(rows.byId(purging.id()).state()).isEqualTo("purged");
            assertThat(rows.contentCopies(purging.content())).isZero();
        });
        assertThat(rows.byId(keptId).state()).isEqualTo("stored");
        Session again = fixtures.signInAgain(s.customer());
        assertThat(download(again, issue.key(), keptId).body()).isEqualTo(kept);
        assertThat(list(again, issue.key())).filteredOn(n -> purging.id().equals(text(n, "id")))
                .singleElement().satisfies(n -> assertThat(n.path("fileName").isNull()).isTrue());
    }

    private static ApiResponse fileWithFiles(Scene s, String title, Map<String, byte[]> files) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("companyCode", s.code());
        body.put("title", title);
        var request = s.customer().client().post("/api/portal/issues").jsonPart("issue", body);
        files.forEach((name, content) -> request.file("files", name, "text/plain", content));
        return request.send();
    }

    private static ApiResponse finish(CompletableFuture<ApiResponse> running) throws InterruptedException {
        try {
            return running.get(60, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AssertionError a) {
                throw a;
            }
            throw new AssertionError("The upload failed", e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("The upload did not finish in 60 seconds", e);
        }
    }

    /** No issue was filed in the company: none for the customer, none in the console. */
    private static void assertNoIssue(Scene s) {
        assertThat(s.customer().client().get("/api/portal/issues").expect(200).json().path("totalElements").asLong())
                .as("issues of the customer").isZero();
        assertThat(s.admin().client().get("/api/console/issues").query("size", 100).expect(200).text())
                .as("console issue list").doesNotContain(s.code() + "-");
    }
}
