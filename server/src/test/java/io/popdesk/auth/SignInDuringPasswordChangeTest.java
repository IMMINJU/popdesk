package io.popdesk.auth;

import static io.popdesk.auth.AuthChecks.assertError;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A sign-in with the old password that began before the password changed and ends after it. Ending
 * the account's sessions cannot catch it, because its session is stored only when the sign-in ends.
 *
 * <p>The order is made in the database, as the attachment tests make theirs ({@code attachment.Hold}
 * and {@code FailureTriggers}, not visible from this package; report: candidate for support): a
 * {@code test_} trigger keeps the INSERT of the new session row waiting while the test holds an
 * advisory lock on a connection of its own. By then the sign-in has checked the password. The
 * password is changed with a reset link, which signs nobody in, so no other session row is written
 * meanwhile. The session table is {@code spring_session} (architecture.md section 4).
 */
class SignInDuringPasswordChangeTest extends ConnectedAppTest {

    private static final String FUNCTION = "test_popdesk_hold_session_insert";

    @Autowired
    PostgreSQLContainer postgres;

    @Test
    @DisplayName("S1A-AC-91 a sign-in that began before the password changed and ended after it leaves a session "
            + "that gets 401")
    void sessionOfASignInFromBeforeTheChangeIsRefused() throws SQLException {
        Session admin = fixtures.signInAdmin();
        Session customer = fixtures.customer(admin, fixtures.createCompany(admin).code());
        String token = PasswordSteps.requestToken(portal(), mail(), customer.email());
        ApiClient browser = portal();
        browser.csrfToken();
        String ip = AuthChecks.freshIp();
        long key = ThreadLocalRandom.current().nextLong(1, Integer.MAX_VALUE);
        String trigger = "test_" + UUID.randomUUID().toString().replace("-", "");
        ApiResponse signedIn;

        createHoldFunction();
        try (Connection lock = DriverManager.getConnection(db.jdbcUrl(), postgres.getUsername(),
                postgres.getPassword())) {
            run(lock, "select pg_advisory_lock(?)", key);
            db.schemaFixture("create trigger " + trigger + " before insert on spring_session for each row "
                    + "execute function " + FUNCTION + "('" + key + "')");
            try {
                CompletableFuture<ApiResponse> signingIn = CompletableFuture.supplyAsync(
                        () -> AuthChecks.signIn(browser, customer.email(), customer.password(), ip));
                awaitSessionInsertHeld();
                clock.advance(Duration.ofSeconds(1));
                PasswordSteps.confirm(portal(), token, PasswordSteps.NEW_PASSWORD).expectStatus(204);
                run(lock, "select pg_advisory_unlock(?)", key);
                signedIn = finish(signingIn);
            } finally {
                // Closing the connection lets a statement that still waits go on, so the trigger can be dropped.
                lock.close();
                db.schemaFixture("drop trigger if exists " + trigger + " on spring_session");
            }
        }

        signedIn.expectStatus(204);
        assertError(browser.get("/api/portal/me").send(), 401, "UNAUTHENTICATED");
    }

    /** The trigger function: waits, at most 20 seconds, while someone holds the advisory lock of its argument. */
    private void createHoldFunction() {
        db.schemaFixture("create or replace function " + FUNCTION + "() returns trigger language plpgsql as $f$\n"
                + "begin\n"
                + "  for i in 1..400 loop\n"
                + "    exit when not exists (select 1 from pg_locks l where l.locktype = 'advisory' and l.granted\n"
                + "      and l.classid = 0 and l.objsubid = 1 and l.objid = TG_ARGV[0]::bigint::oid);\n"
                + "    perform pg_sleep(0.05);\n"
                + "  end loop;\n"
                + "  return NEW;\n"
                + "end $f$");
    }

    private void awaitSessionInsertHeld() {
        Wait.until("the sign-in's session INSERT held in the trigger", () -> {
            var found = db.rows("select pid from pg_stat_activity where wait_event = 'PgSleep' "
                    + "and pid <> pg_backend_pid() and datname = current_database()");
            return found.size() == 1 ? found.getFirst() : null;
        });
    }

    private static void run(Connection connection, String sql, long key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, key);
            statement.executeQuery();
        }
    }

    private static ApiResponse finish(CompletableFuture<ApiResponse> running) {
        try {
            return running.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while the sign-in finished", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AssertionError failed) {
                throw failed;
            }
            throw new AssertionError("The sign-in failed: " + e.getCause(), e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("The sign-in did not finish within 30 seconds of the release", e);
        }
    }
}
