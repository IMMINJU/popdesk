package io.popdesk.start;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.start.Database.Lock;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The single-instance lock: an advisory lock with two int keys on a connection of its own, taken
 * before Flyway. How long a second app tries is {@code POPDESK_INSTANCE_LOCK_WAIT} (ISO-8601
 * duration); the criterion names no key, so this name is this tab's assumption (see the report).
 */
class InstanceLockTest extends OwnStartTest {

    static final String LOCK_WAIT = "POPDESK_INSTANCE_LOCK_WAIT";

    @Test
    @DisplayName("S0-AC-11 a running app holds one advisory lock with two int keys on an idle autocommit connection")
    void lockVisibleInPgLocks() {
        Database db = postgres.newDatabase();
        try (Boot app = Boot.start(Settings.firstInstall(db))) {
            app.assertStarted();

            List<Lock> locks = db.twoKeyLocks();
            assertThat(locks).as("granted advisory locks with two int keys: %s", db.advisoryLocks()).hasSize(1);
            assertThat(db.backendState(locks.getFirst().pid())).as("state of the lock's connection").isEqualTo("idle");
        }
    }

    @Test
    @DisplayName("S0-AC-11 a second app stops after the lock wait, says another instance runs, and never runs Flyway")
    void secondAppStops() {
        Database db = postgres.newDatabase();
        try (Boot first = Boot.start(Settings.firstInstall(db))) {
            first.assertStarted();
            List<Lock> before = db.twoKeyLocks();
            assertThat(before).as("the first app's lock").hasSize(1);

            Boot.Background second = Boot.startInBackground(Settings.firstInstall(db)
                    .withFixtureMigrations("second-instance")
                    .set(LOCK_WAIT, "PT3S"));
            try {
                Boot app = second.await(Duration.ofSeconds(60));
                app.assertDidNotStart("another instance holds the lock");

                assertThat(app.logLines()).as("a WARN or ERROR line about another instance")
                        .anyMatch(line -> (line.contains("WARN") || line.contains("ERROR"))
                                && line.toLowerCase(Locale.ROOT).contains("instance"));
                assertThat(db.table("test_second_instance_ran")).as("table of the second app's fixture migration")
                        .isEmpty();
                assertThat(db.twoKeyLocks()).as("the first app keeps its lock").isEqualTo(before);
            } finally {
                second.close(db);
            }
        }
    }

    @Test
    @DisplayName("S0-AC-11 an app waiting for the lock takes it over when the first app stops")
    void waitingAppTakesOver() {
        Database db = postgres.newDatabase();
        Boot first = Boot.start(Settings.firstInstall(db));
        Boot.Background second = null;
        try {
            first.assertStarted();
            int firstPid = onlyLock(db).pid();

            second = Boot.startInBackground(Settings.firstInstall(db).set(LOCK_WAIT, "PT60S"));
            Boot.Background waiting = second;
            Wait.holds("the second app waits while the first holds the lock", Duration.ofSeconds(3),
                    () -> !waiting.done());

            first.close();
            Boot app = second.await(START).assertStarted();
            assertReady(app);
            assertThat(onlyLock(db).pid()).as("pid holding the lock").isNotEqualTo(firstPid);
        } finally {
            first.close();
            if (second != null) {
                second.close(db);
            }
        }
    }

    @Test
    @DisplayName("S0-AC-11 when the lock's connection is cut the app takes the lock again")
    void lockTakenAgainAfterConnectionLoss() {
        Database db = postgres.newDatabase();
        try (Boot app = Boot.start(Settings.firstInstall(db))) {
            app.assertStarted();
            int cut = onlyLock(db).pid();

            db.terminate(cut);

            Wait.untilAsserted("a new lock with two int keys", () -> assertThat(db.twoKeyLocks())
                    .as("granted locks with two int keys after cutting pid %s", cut)
                    .singleElement()
                    .satisfies(lock -> assertThat(lock.pid()).isNotEqualTo(cut)));
        }
    }

    private static Lock onlyLock(Database db) {
        List<Lock> locks = db.twoKeyLocks();
        assertThat(locks).as("granted advisory locks with two int keys: %s", db.advisoryLocks()).hasSize(1);
        return locks.getFirst();
    }
}
