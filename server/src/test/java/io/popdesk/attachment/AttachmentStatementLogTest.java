package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.bytes;
import static io.popdesk.attachment.AttachmentSteps.download;
import static io.popdesk.attachment.AttachmentSteps.upload;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.AttachmentSteps.Scene;
import io.popdesk.attachment.StatementLog.Entry;
import io.popdesk.attachment.StatementLog.Window;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Unique;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * What the app sends to PostgreSQL when a file is uploaded and downloaded, read from the server
 * log ({@code log_statement=all}, bind parameters on {@code DETAIL} lines). This class has a context
 * of its own ({@link StatementLog.Config}).
 *
 * <p>The tests find statements by values they made: the file name (in the {@code pending} row), the
 * file content (PostgreSQL logs a {@code bytea} parameter as {@code \x} and hex), and the
 * attachment ID.
 */
@Import(StatementLog.Config.class)
class AttachmentStatementLogTest extends BareAppTest {

    private static final Pattern BEGIN = Pattern.compile("(?i)(statement|execute [^:]*):\\s*(begin|start transaction)\\b");
    private static final Pattern END = Pattern.compile("(?i)(statement|execute [^:]*):\\s*(commit|rollback|end)\\b");
    private static final Pattern COMMIT = Pattern.compile("(?i)(statement|execute [^:]*):\\s*(commit|end)\\b");
    private static final Pattern ISSUE_LOCK = Pattern.compile("(?i)\\bfor\\s+no\\s+key\\s+update\\b");
    private static final Pattern UPDATE = Pattern.compile("(?i)(statement|execute [^:]*):\\s*update\\b");
    private static final Pattern SUBSTRING = Pattern.compile("(?i)(statement|execute [^:]*):.*\\bsubstring\\s*\\(");

    @Autowired
    PostgreSQLContainer postgres;

    private AttachmentSteps steps;
    private StatementLog sql;

    @BeforeEach
    void setUp() {
        steps = new AttachmentSteps(fixtures);
        sql = new StatementLog(db, postgres);
    }

    @Test
    @DisplayName("S0-AC-56 the pending row is committed before the content is written, the content is committed before the transaction that locks the issue begins, and that transaction updates the row")
    void threeStepsInOrder() {
        sql.assertLogsEveryStatement();
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        String name = Unique.text("order") + ".txt";
        byte[] content = AttachmentSteps.text("Content written in order " + Unique.number());
        String hex = HexFormat.of().formatHex(content);

        Window window = sql.start();
        ApiResponse created = upload(s.customer(), issue.key(), name, content);
        String serverLog = sql.end(window);

        created.expectStatus(201);
        String id = created.jsonText("id");
        List<Entry> entries = StatementLog.entries(serverLog);
        Entry pending = first(entries, 0, e -> e.contains(name), "a statement with the file name " + name);
        Entry write = first(entries, 0, e -> e.text().toLowerCase(Locale.ROOT).contains(hex),
                "a statement with the file content");
        assertThat(pending.index()).as("pending row before the content: %s", entries).isLessThan(write.index());
        assertThat(committedBetween(entries, pending, write))
                .as("the pending row's transaction ends before the content is written: %s", entries).isTrue();
        assertThat(entries.subList(0, write.index())).as("no issue lock before the content is written")
                .noneMatch(e -> e.matches(ISSUE_LOCK));
        Entry lock = first(entries, write.index(), e -> e.matches(ISSUE_LOCK), "FOR NO KEY UPDATE after the content");
        assertThat(committedBetween(entries, write, lock))
                .as("the content's transaction ends before the issue is locked: %s", entries).isTrue();
        assertThat(lastBegin(entries, lock))
                .as("the transaction that locks the issue begins after the content is written: %s", entries)
                .isGreaterThan(write.index());
        Entry stored = first(entries, lock.index(),
                e -> e.pid().equals(lock.pid()) && e.matches(UPDATE) && e.contains(id),
                "an UPDATE of attachment " + id + " in the transaction that locked the issue");
        assertThat(entries.subList(lock.index(), stored.index()))
                .as("the issue lock and the attachment update are in one transaction")
                .noneMatch(e -> e.pid().equals(lock.pid()) && e.matches(END));
    }

    @Test
    @DisplayName("S0-AC-59 a 2,500,000-byte download reads the content in three substring chunks of attachment_content")
    void downloadReadsChunks() {
        sql.assertLogsEveryStatement();
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        byte[] content = bytes(2_500_000, Unique.number());
        String id = upload(s.customer(), issue.key(), Unique.text("chunks") + ".bin", content).expectStatus(201)
                .jsonText("id");

        Window window = sql.start();
        ApiResponse downloaded = download(s.customer(), issue.key(), id);
        String serverLog = sql.end(window);

        assertThat(downloaded.status()).isEqualTo(200);
        assertThat(downloaded.body()).isEqualTo(content);
        List<Entry> chunks = StatementLog.entries(serverLog).stream()
                .filter(e -> e.matches(SUBSTRING) && e.text().contains(AttachmentRows.CONTENT_TABLE))
                .toList();
        assertThat(chunks).as("substring reads of attachment_content").hasSize(3);
    }

    /**
     * Index of the last {@code BEGIN} on {@code statement}'s connection before it, or -1 when it ran
     * without one (autocommit).
     */
    private static int lastBegin(List<Entry> entries, Entry statement) {
        int found = -1;
        for (Entry e : entries.subList(0, statement.index())) {
            if (e.pid().equals(statement.pid()) && e.matches(BEGIN)) {
                found = e.index();
            }
        }
        return found;
    }

    /**
     * Whether the transaction that ran {@code pending} ended before {@code write}: either it ran
     * without an open {@code BEGIN} (autocommit), or a {@code COMMIT} on the same connection comes
     * between the two.
     */
    private static boolean committedBetween(List<Entry> entries, Entry pending, Entry write) {
        boolean open = false;
        for (Entry e : entries.subList(0, pending.index())) {
            if (e.pid().equals(pending.pid())) {
                if (e.matches(BEGIN)) {
                    open = true;
                } else if (e.matches(END)) {
                    open = false;
                }
            }
        }
        if (!open) {
            return true;
        }
        return entries.subList(pending.index() + 1, write.index()).stream()
                .anyMatch(e -> e.pid().equals(pending.pid()) && e.matches(COMMIT));
    }

    private static Entry first(List<Entry> entries, int from, Predicate<Entry> test, String what) {
        return entries.subList(from, entries.size()).stream().filter(test).findFirst()
                .orElseThrow(() -> new AssertionError("No " + what + " in the PostgreSQL log window: " + entries));
    }
}
