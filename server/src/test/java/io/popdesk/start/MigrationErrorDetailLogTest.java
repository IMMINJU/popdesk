package io.popdesk.start;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A start that fails on a database error, with a {@code POPDESK_DB_URL} that does not carry
 * {@code logServerErrorDetail=false} (verify.md V-14): the operator changed the URL and left the flag
 * out. The error's DETAIL quotes a row value; it must not reach the log, where a failed start is
 * written with its whole chain of causes.
 *
 * <p>The failure is a fixture migration ({@code start/error-detail}) that builds a unique index over
 * a duplicated value.
 */
class MigrationErrorDetailLogTest extends OwnStartTest {

    /** The value the fixture migration duplicates; PostgreSQL quotes it in the error's DETAIL. */
    static final String ROW_VALUE = "v14-row-value-in-detail";

    @Test
    @DisplayName("V-14 S0-AC-86 without logServerErrorDetail=false in POPDESK_DB_URL, a failed migration leaves no row value from the error's DETAIL in the log")
    void failedMigrationWithoutTheFlag() {
        Database db = postgres.newDatabase();
        Settings settings = Settings.firstInstall(db)
                .set("POPDESK_DB_URL", postgres.jdbcUrl(db.name()))
                .withFixtureMigrations("error-detail");

        try (Boot app = Boot.start(settings)) {
            app.assertDidNotStart("migration 9001 fails on a duplicated value");

            assertThat(app.logLines()).as("a line naming the failed migration 9001")
                    .anyMatch(line -> line.contains("9001") && line.contains("ERROR"));
            assertThat(app.log()).as("the log of the failed start").doesNotContain(ROW_VALUE);
        }
    }
}
