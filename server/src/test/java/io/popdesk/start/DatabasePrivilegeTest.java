package io.popdesk.start;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** An outside database whose user may connect but may not create tables. */
class DatabasePrivilegeTest extends OwnStartTest {

    @Test
    @DisplayName("S0-AC-10 a database user without CREATE stops the start before migrations and the log says what is missing")
    void userWithoutCreate() {
        assertStopsBeforeMigrations(postgres.newDatabaseWithoutCreate());
    }

    @Test
    @DisplayName("S0-AC-10 a database user with CREATE on the database but not on the schema stops the start before migrations")
    void userWithoutSchemaCreate() {
        assertStopsBeforeMigrations(postgres.newDatabaseWithoutSchemaCreate());
    }

    private static void assertStopsBeforeMigrations(Database db) {
        Boot app = Boot.start(Settings.firstInstall(db));
        app.assertDidNotStart("user " + db.user() + " may not create tables");

        assertThat(app.logLines()).as("a line naming the missing CREATE privilege of %s", db.user())
                .anyMatch(line -> line.contains("CREATE") && line.contains(db.user()));
        assertThat(db.table("flyway_schema_history")).as("flyway_schema_history").isEmpty();
        assertThat(db.tables()).as("tables").isEmpty();
    }
}
