package io.popdesk.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.Transactions;
import io.popdesk.helpdesk.audit.AuditAction;
import io.popdesk.helpdesk.audit.AuditLog;
import io.popdesk.support.AuditChainMath;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A transaction that fails on a lock while its entries join the chain is run again from the start
 * ({@code Transactions.run}); the entries of the failed attempt must not join with those of the
 * second. No request can hold the chain's head row for 5 seconds, so this test holds it on a
 * connection of its own and records through the app's own beans.
 */
class AuditRetryTest extends BareAppTest {

    @Autowired
    Transactions transactions;

    @Autowired
    AuditLog audit;

    @Autowired
    PostgreSQLContainer postgres;

    private Connection holdTheHeadRow() throws SQLException {
        Connection connection = DriverManager.getConnection(db.jdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
        connection.setSchema(db.schema());
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("select 1 from audit_chain for no key update");
        }
        return connection;
    }

    @Test
    @DisplayName("S1B-AC-11 a transaction retried after a lock timeout at the chain's head leaves each of its "
            + "entries once, and the chain stays valid")
    void retriedTransactionAppendsItsEntriesOnce() throws SQLException, InterruptedException {
        Session admin = fixtures.admin(fixtures.signInAdmin());
        String file = "/tmp/" + Unique.text("retried") + ".dump";
        AtomicInteger attempts = new AtomicInteger();
        ExecutorService thread = Executors.newSingleThreadExecutor();
        Connection lock = holdTheHeadRow();
        try {
            Future<?> recording = thread.submit(() -> transactions.run(() -> {
                attempts.incrementAndGet();
                audit.recordBySystem(AuditAction.BACKUP_CREATED, Map.of("type", "DATABASE"), Map.of("file", file));
                audit.recordBySystem(AuditAction.BACKUP_CREATED, Map.of("type", "DATABASE"), Map.of("file", file));
            }));
            // The first attempt gives up on the head row after the app's lock_timeout of 5 seconds.
            Wait.until("the second attempt of the transaction", Duration.ofSeconds(30),
                    () -> attempts.get() >= 2 ? Boolean.TRUE : null);
            lock.rollback();
            try {
                recording.get(30, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException e) {
                throw new AssertionError("The retried transaction did not commit: " + e, e);
            }
        } finally {
            lock.close();
            thread.shutdownNow();
        }

        assertThat(attempts.get()).as("attempts of the transaction").isEqualTo(2);
        assertThat(db.count("select count(*) from audit_log where changes ->> 'file' = ?", file))
                .as("entries of the transaction: the two of the attempt that committed").isEqualTo(2);
        AuditChainMath.assertValid(admin.client());
    }
}
