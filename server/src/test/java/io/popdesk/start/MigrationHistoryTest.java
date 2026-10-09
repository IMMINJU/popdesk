package io.popdesk.start;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.start.Database.Table;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Migration histories the app must refuse: newer than the app, or with a failed migration. Fixture
 * migrations live in {@code src/test/resources/start/<folder>} with versions from 9000 up, above
 * the app's own; only these tests add them to {@code spring.flyway.locations}.
 */
class MigrationHistoryTest extends OwnStartTest {

    /** What the log points at for recovery: the operations documentation. */
    static final String RECOVERY_DOC = "docs/operate";

    @Test
    @DisplayName("S0-AC-07 a database with a migration newer than the app stops the start and says to restore the backup")
    void newerSchemaStopsStart() {
        Database db = postgres.newDatabase();
        try (Boot first = Boot.start(Settings.firstInstall(db))) {
            first.assertStarted();
        }
        Table history = db.table("flyway_schema_history")
                .orElseThrow(() -> new AssertionError("no flyway_schema_history in " + db.tables()));
        db.execute("insert into " + history.qualified() + " (installed_rank, version, description, type, script, "
                + "checksum, installed_by, execution_time, success) select coalesce(max(installed_rank), 0) + 1, "
                + "'9999', 'from a newer app', 'SQL', 'V9999__from_a_newer_app.sql', 0, current_user, 0, true from "
                + history.qualified());

        Boot app = Boot.start(Settings.firstInstall(db));
        app.assertDidNotStart("the database has migration 9999, newer than the app");

        assertThat(app.logLines()).as("a line with the database's version that says to restore the backup")
                .anyMatch(line -> line.contains("9999") && line.toLowerCase(Locale.ROOT).contains("backup"));
    }

    @Test
    @DisplayName("S0-AC-08 a migration that failed in its transaction stops every start, names the version and the recovery doc, makes no admin")
    void failedInTransactionStopsStart() {
        Database db = postgres.newDatabase();
        Settings settings = Settings.firstInstall(db).withFixtureMigrations("failed-in-transaction");

        for (int attempt = 1; attempt <= 2; attempt++) {
            Boot app = Boot.start(settings);
            app.assertDidNotStart("migration 9001 fails (start " + attempt + ")");
            assertNamesFailedVersion(app, "9001");
            assertThat(db.accountRows()).as("account rows after start %s", attempt).isEmpty();
        }
    }

    @Test
    @DisplayName("S0-AC-08 a failed CREATE INDEX CONCURRENTLY ends within 30 seconds and stops the next start, makes no admin")
    void failedConcurrentlyStopsStart() {
        Database db = postgres.newDatabase();
        Settings settings = Settings.firstInstall(db).withFixtureMigrations("failed-concurrently");

        Boot.Background first = Boot.startInBackground(settings);
        try {
            Boot app = first.await(Duration.ofSeconds(30));
            app.assertDidNotStart("migration 9002 fails");
            assertNamesFailedVersion(app, "9002");
        } finally {
            first.close(db);
        }
        Table history = db.table("flyway_schema_history")
                .orElseThrow(() -> new AssertionError("no flyway_schema_history in " + db.tables()));
        assertThat(db.query("select version from " + history.qualified() + " where not success", rs -> rs.getString(1)))
                .as("failed migrations in the history").containsExactly("9002");

        Boot again = Boot.start(settings);
        again.assertDidNotStart("the history has failed migration 9002");
        assertNamesFailedVersion(again, "9002");
        assertThat(db.accountRows()).as("account rows").isEmpty();
    }

    @Test
    @DisplayName("S0-AC-08 a CREATE INDEX CONCURRENTLY migration does not wait on Flyway's lock and the start ends within 30 seconds")
    void concurrentlyMigrationFinishes() {
        Database db = postgres.newDatabase();
        Boot.Background start = Boot.startInBackground(Settings.firstInstall(db).withFixtureMigrations("concurrently"));
        try {
            start.await(Duration.ofSeconds(30)).assertStarted();

            assertThat(db.query("select i.indisvalid from pg_index i join pg_class c on c.oid = i.indexrelid "
                    + "where c.relname = 'test_indexed_code'", rs -> rs.getBoolean(1)))
                    .as("index test_indexed_code, valid").containsExactly(true);
        } finally {
            start.close(db);
        }
    }

    private static void assertNamesFailedVersion(Boot app, String version) {
        List<String> lines = app.logLines();
        assertThat(lines).as("a line naming failed migration %s and the recovery steps in %s", version, RECOVERY_DOC)
                .anyMatch(line -> line.contains(version) && line.contains(RECOVERY_DOC));
    }
}
