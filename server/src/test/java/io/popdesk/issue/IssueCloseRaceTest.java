package io.popdesk.issue;

import static io.popdesk.issue.IssueFlow.time;
import static io.popdesk.issue.IssueSteps.items;
import static io.popdesk.issue.IssueSteps.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow.World;
import io.popdesk.issue.StatementLog.Window;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.AppLog;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Concurrently;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.HeldWrite;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * Closing an issue while a comment, an attachment, or an edit of it is on its way (STATUS-7). The two
 * requests are made to overlap in the database ({@link HeldWrite}): the closing request is kept
 * waiting at its UPDATE of the issue row, the clock moves a second, the other request is sent, and
 * the closing one is let go once the other has finished or waits for a lock.
 *
 * <p>Which request wins is not fixed. What is left must be what one of the two orders leaves: a
 * write that was stored is not later than the closing, and a write that was refused left nothing.
 * PostgreSQL logs every statement in this context ({@link StatementLog.Config}, the context of
 * {@link IssueConcurrencyTest}), so a deadlock shows in its log.
 */
@Import(StatementLog.Config.class)
class IssueCloseRaceTest extends BareAppTest {

    /** How long the closing request is held when the other neither finishes nor shows in the database. */
    private static final Duration HELD_AT_MOST = Duration.ofSeconds(2);

    /** What is written under the issue while it is being closed. */
    enum Write {
        COMMENT,
        ATTACHMENT,
        EDIT
    }

    @Autowired
    PostgreSQLContainer postgres;

    private IssueFlow flow;
    private StatementLog sql;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
        sql = new StatementLog(db, postgres);
    }

    /** The agent's write, marked with {@code marker}: the comment's text, the file's name, or the new title. */
    private static ApiResponse send(Write write, Session agent, String key, String marker, long version) {
        String issue = "/api/console/issues/" + key;
        return switch (write) {
            case COMMENT -> agent.client().post(issue + "/comments").json(Map.of("content", marker)).send();
            case ATTACHMENT -> agent.client().post(issue + "/attachments")
                    .file("file", marker, "text/plain", "raced".getBytes(StandardCharsets.UTF_8)).send();
            case EDIT -> IssueFlow.editRequest(agent, key, Map.of("title", marker, "version", version)).send();
        };
    }

    /** The comment or attachment of the issue whose {@code field} is the marker, or null. */
    private static JsonNode stored(Session agent, String key, String list, String field, String marker) {
        ApiResponse page = agent.client().get("/api/console/issues/" + key + "/" + list).query("size", 100)
                .expect(200);
        return items(page).stream().filter(n -> marker.equals(text(n, field))).findFirst().orElse(null);
    }

    @ParameterizedTest(name = "closing and {0}")
    @EnumSource(Write.class)
    @DisplayName("S3-AC-52 a comment, an attachment, or an edit that overlaps the closing is stored before it or refused with 400 ISSUE_CLOSED, without a 5xx or a deadlock")
    void writeOverlappingTheClosing(Write write) {
        sql.assertLogsEveryStatement();
        World w = flow.world();
        String title = Unique.text("Closing race");
        Issue issue = flow.fileIn(w.reporter(), NewIssue.in(w.code()).title(title), w.agent(), "CONFIRMED");
        String issueTable = new IssueTables(db).find(w.company().legalName(), title).issue().table().name();
        long version = flow.version(w.agent(), issue.key());
        String marker = "raced-" + Unique.number() + ".txt";
        AppLog.Mark appMark = log.mark();
        Window window = sql.start();
        ApiResponse closing;
        ApiResponse writing;

        try (HeldWrite hold = new HeldWrite(db, postgres, issueTable, "update", title)) {
            CompletableFuture<ApiResponse> close = CompletableFuture.supplyAsync(
                    () -> IssueFlow.closeRequest(w.reporter(), issue.key()).send());
            hold.awaitHeld("the UPDATE that closes the issue");
            flow.tick();
            CompletableFuture<ApiResponse> other = CompletableFuture.supplyAsync(
                    () -> send(write, w.agent(), issue.key(), marker, version));
            long heldUntil = System.nanoTime() + HELD_AT_MOST.toNanos();
            Wait.until("the other request finished, waits for a lock, or had " + HELD_AT_MOST,
                    () -> other.isDone() || hold.waitingForALock() > 0 || System.nanoTime() > heldUntil
                            ? Boolean.TRUE : null);
            hold.release();
            closing = Concurrently.finish(close, "the closing request");
            writing = Concurrently.finish(other, "the other request");
        }
        String serverLog = sql.end(window).toLowerCase(Locale.ROOT);

        assertThat(closing.status()).as("closing: " + closing.snippet()).isEqualTo(200);
        assertThat(writing.status()).as(write + ": " + writing.snippet()).isLessThan(500);
        JsonNode after = flow.detail(w.agent(), issue.key());
        assertThat(text(after, "status")).as("status after both requests").isEqualTo("CLOSED");
        Instant closedAt = time(after, "closedAt");
        assertThat(closedAt).as("closedAt after both requests").isNotNull();
        boolean stored = writing.status() == 200 || writing.status() == 201;
        if (!stored) {
            // An edit that comes after the closing carries a version from before it. It is still told that
            // the issue is closed: that comes before the version (design.md section 2).
            assertThat(writing.status()).as(write + " after the closing: " + writing.snippet()).isEqualTo(400);
            assertThat(writing.errorCode()).as("code of the refused " + write).isEqualTo("ISSUE_CLOSED");
        }
        switch (write) {
            case COMMENT -> {
                JsonNode comment = stored(w.agent(), issue.key(), "comments", "content", marker);
                assertThat(comment != null).as("the comment is under the closed issue").isEqualTo(stored);
                if (comment != null) {
                    assertThat(time(comment, "createdAt")).as("time of the comment, not after the closing")
                            .isBeforeOrEqualTo(closedAt);
                }
            }
            case ATTACHMENT -> {
                JsonNode file = stored(w.agent(), issue.key(), "attachments", "fileName", marker);
                assertThat(file != null).as("the file is under the closed issue").isEqualTo(stored);
                if (file != null) {
                    assertThat(time(file, "createdAt")).as("time of the attachment, not after the closing")
                            .isBeforeOrEqualTo(closedAt);
                }
            }
            case EDIT -> {
                assertThat(text(after, "title")).as("title after both requests").isEqualTo(stored ? marker : title);
                List<String> fields = flow.history(w.agent(), issue.key()).stream()
                        .filter(entry -> "CLOSED".equals(text(entry, "newValue")) || "TITLE".equals(text(entry, "field")))
                        .map(entry -> text(entry, "field")).toList();
                assertThat(fields).as("the title entry and the closing entry of the history, in order")
                        .isEqualTo(stored ? List.of("TITLE", "STATUS") : List.of("STATUS"));
            }
        }
        assertThat(serverLog).as("statements in the window").contains("update");
        assertThat(serverLog).as("PostgreSQL log").doesNotContain("deadlock detected");
        assertThat(log.since(appMark)).as("app log").doesNotContain("40P01");
    }
}
