package io.popdesk.audit;

import static io.popdesk.audit.AuditSteps.ended;
import static io.popdesk.audit.AuditSteps.entries;
import static io.popdesk.audit.AuditSteps.export;
import static io.popdesk.audit.AuditSteps.exportHead;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * At most 2 exports run at once; the next one is 503 {@code SERVER_BUSY} with {@code Retry-After}.
 *
 * <p>The test keeps two exports running without a fixed wait: on a connection of its own it locks
 * the audit table against new rows ({@code SHARE} mode; reading still works). An export records
 * itself in the audit log when it starts, so each of the two waits there, holding its place, until
 * the test lets go. {@code HEAD} writes nothing, so it is the probe that shows when both places are
 * taken. The app's statements give up on a lock after 5 seconds, so the test lets go well before.
 */
class AuditExportLimitTest extends BareAppTest {

    @Autowired
    PostgreSQLContainer postgres;

    private Connection lockAuditTable() {
        try {
            Connection connection = DriverManager.getConnection(db.jdbcUrl(), postgres.getUsername(),
                    postgres.getPassword());
            connection.setSchema(db.schema());
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("lock table audit_log in share mode");
            }
            return connection;
        } catch (SQLException e) {
            throw new AssertionError("Could not lock the audit table against new rows", e);
        }
    }

    private static void letGo(Connection lock) {
        try {
            lock.rollback();
            lock.close();
        } catch (SQLException e) {
            throw new AssertionError("Could not release the lock on the audit table", e);
        }
    }

    @Test
    @DisplayName("S1B-AC-28 with 2 exports running, a third export and a HEAD on the export are 503 SERVER_BUSY "
            + "with Retry-After, and the refused export leaves no entry; after the two end, an export works again")
    void thirdExportIsRefused() {
        Session admin = fixtures.admin(fixtures.signInAdmin());
        Session firstBrowser = fixtures.signInAgain(admin);
        Session secondBrowser = fixtures.signInAgain(admin);
        Object[] mine = {"actorAccountId", admin.accountId(), "action", "AUDIT_EXPORTED"};
        ExecutorService threads = Executors.newFixedThreadPool(2);
        Connection lock = lockAuditTable();
        boolean held = true;
        try {
            List<CompletableFuture<ApiResponse>> running = List.of(
                    CompletableFuture.supplyAsync(() -> export(firstBrowser, "JSONL", mine), threads),
                    CompletableFuture.supplyAsync(() -> export(secondBrowser, "CSV", mine), threads));

            ApiResponse headRefused = Wait.until("a HEAD refused while 2 exports run", () -> {
                if (running.stream().allMatch(CompletableFuture::isDone)) {
                    throw new AssertionError("Both exports ended instead of running while the audit table takes "
                            + "no new rows: statuses " + running.stream().map(export -> ended(export).status())
                                    .toList());
                }
                ApiResponse head = exportHead(admin, "JSONL", mine);
                return head.status() == 503 ? head : null;
            });
            ApiResponse refused = export(admin, "JSONL", mine);

            assertThat(headRefused.header("Retry-After")).as("Retry-After of the HEAD").isNotNull().matches("\\d+");
            assertThat(headRefused.body()).as("body of the HEAD").isEmpty();
            assertThat(refused.status()).as(refused.request()).isEqualTo(503);
            assertThat(refused.errorCode()).isEqualTo("SERVER_BUSY");
            assertThat(refused.header("Retry-After")).as("Retry-After").isNotNull().matches("\\d+");
            assertThat(refused.header("Content-Disposition")).as("Content-Disposition of the 503").isNull();

            letGo(lock);
            held = false;
            assertThat(running.stream().map(export -> ended(export).status()).toList())
                    .as("statuses of the two exports that were running").containsExactly(200, 200);
            ApiResponse after = export(admin, "JSONL", mine);
            assertThat(after.status()).as("an export after the two ended").isEqualTo(200);
            assertThat(exportHead(admin, "JSONL", mine).status()).as("a HEAD after the two ended").isEqualTo(200);
            assertThat(entries(admin, mine)).as("AUDIT_EXPORTED entries: the two that ran and the one after, not "
                    + "the refused one").hasSize(3);
        } finally {
            if (held) {
                letGo(lock);
            }
            threads.shutdownNow();
        }
    }
}
