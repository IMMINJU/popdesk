package io.popdesk.start;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.start.Database.Lock;
import io.popdesk.support.Wait;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The log of an app that lost the single-instance lock while another session holds it (verify.md
 * V-11). The app tries again every second; it must not write a warning every second. A change of
 * state is logged once, and the same state at most once a minute.
 *
 * <p>The other instance is a connection of the test: it queues for the app's lock (the keys are read
 * from {@code pg_locks}), the test ends the app's lock connection, and the queued session gets the
 * lock before the app can take it again. Only an upper limit on the lines is checked.
 */
class InstanceLockWaitLogTest extends OwnStartTest {

    /** How long the app is watched while it cannot take the lock: several of its one-second tries. */
    private static final Duration WATCHED = Duration.ofSeconds(8);

    /** Losing the lock and finding it taken are two changes of state; one line more is allowed. */
    private static final int MOST_LINES = 3;

    @Test
    @DisplayName("V-11 S0-AC-11 while another session holds the lock, the app that lost it does not log a warning every second")
    void waitingAppDoesNotLogEverySecond() throws Exception {
        Database db = postgres.newDatabase();
        try (Boot app = Boot.start(Settings.firstInstall(db))) {
            app.assertStarted();
            Lock appLock = onlyLock(db);

            try (Connection other = postgres.superuserConnection(db.name())) {
                int otherPid = pid(other);
                CompletableFuture<Void> taking = CompletableFuture.runAsync(() -> lock(other, appLock));
                Wait.until("the other session queued for the app's lock", () -> db.advisoryLocks().stream()
                        .anyMatch(l -> l.pid() == otherPid && !l.granted()) ? true : null);
                int mark = Console.mark();

                db.terminate(appLock.pid());

                Wait.until("the other session holding the lock", () -> taking.isDone() && db.twoKeyLocks().stream()
                        .anyMatch(l -> l.pid() == otherPid) ? true : null);
                taking.join();
                Wait.until("a WARN line about the lock", () -> lockWarnings(mark).isEmpty() ? null : true);

                Wait.holds("at most " + MOST_LINES + " WARN lines about the lock while the other session holds it",
                        WATCHED, () -> lockWarnings(mark).size() <= MOST_LINES);
                List<String> warnings = lockWarnings(mark);
                assertThat(warnings).as("WARN lines about the lock in %s of waiting", WATCHED)
                        .hasSizeLessThanOrEqualTo(MOST_LINES);
                assertThat(db.twoKeyLocks()).as("the lock stays with the other session while the app waits")
                        .singleElement().satisfies(l -> assertThat(l.pid()).isEqualTo(otherPid));
            }

            Wait.untilAsserted("the app takes the lock again once the other session is gone",
                    () -> assertThat(db.twoKeyLocks()).as("granted locks with two int keys").hasSize(1));
        }
    }

    /** WARN lines that mention the lock, written since {@code mark}. */
    private static List<String> lockWarnings(int mark) {
        return Console.linesSince(mark).stream()
                .filter(line -> line.contains("WARN") && line.toLowerCase(Locale.ROOT).contains("lock"))
                .toList();
    }

    /** Blocks until this connection holds the same two-key advisory lock as {@code appLock}. */
    private static void lock(Connection c, Lock appLock) {
        try (PreparedStatement s = c.prepareStatement("select pg_advisory_lock(?, ?)")) {
            s.setInt(1, (int) appLock.classid());
            s.setInt(2, (int) appLock.objid());
            s.executeQuery().close();
        } catch (SQLException e) {
            throw new AssertionError("The other session could not take the app's lock: " + e.getMessage(), e);
        }
    }

    private static int pid(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("select pg_backend_pid()")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static Lock onlyLock(Database db) {
        List<Lock> locks = db.twoKeyLocks();
        assertThat(locks).as("granted advisory locks with two int keys: %s", db.advisoryLocks()).hasSize(1);
        return locks.getFirst();
    }
}
