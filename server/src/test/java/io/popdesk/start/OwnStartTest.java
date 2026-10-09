package io.popdesk.start;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.Wait;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * Base for tests that start the app themselves ({@link Boot}) because no shared test context can
 * show what they check: a first start on an empty database, starting twice, a broken migration
 * history, missing settings. One PostgreSQL container per test class; every test takes a new
 * database from it and closes every app it started.
 */
abstract class OwnStartTest {

    /** Long enough for a first start with all migrations on a small machine. */
    static final Duration START = Duration.ofSeconds(90);

    static StartPostgres postgres;

    @BeforeAll
    static void startPostgres() {
        Console.install();
        postgres = StartPostgres.start();
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.close();
        }
    }

    /** Readiness on the management port is UP (200) within a few seconds. */
    static void assertReady(Boot app) {
        Wait.untilAsserted("readiness UP", () -> {
            ApiResponse readiness = app.management("/actuator/health/readiness");
            assertThat(readiness.status()).as(readiness.toString() + " " + readiness.snippet()).isEqualTo(200);
            assertThat(readiness.json().path("status").asString()).isEqualTo("UP");
        });
    }

    /** The first admin of {@code email} exists once: one account row with that email. */
    static void assertOneAccountRow(Database db, String email) {
        assertThat(db.accountRows()).as("account rows (rows with an Argon2id hash) in %s", db).hasSize(1);
        assertThat(db.accountRows().getFirst().contains(email)).as("the account row holds %s", email).isTrue();
    }
}
