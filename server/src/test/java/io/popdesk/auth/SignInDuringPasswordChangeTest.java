package io.popdesk.auth;

import static io.popdesk.auth.AuthChecks.assertError;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.HeldWrite;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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
 * <p>The order is made in the database ({@link HeldWrite}): a trigger keeps the INSERT of the new
 * session row waiting until the test lets it go on. By then the sign-in has checked the password. The
 * password is changed with a reset link, which signs nobody in, so no other session row is written
 * meanwhile. The session table is {@code spring_session} (architecture.md section 4).
 */
class SignInDuringPasswordChangeTest extends ConnectedAppTest {

    @Autowired
    PostgreSQLContainer postgres;

    @Test
    @DisplayName("S1A-AC-91 a sign-in that began before the password changed and ended after it leaves a session "
            + "that gets 401")
    void sessionOfASignInFromBeforeTheChangeIsRefused() {
        Session admin = fixtures.signInAdmin();
        Session customer = fixtures.customer(admin, fixtures.createCompany(admin).code());
        String token = PasswordSteps.requestToken(portal(), mail(), customer.email());
        ApiClient browser = portal();
        browser.csrfToken();
        String ip = AuthChecks.freshIp();
        ApiResponse signedIn;

        try (HeldWrite hold = new HeldWrite(db, postgres, "spring_session", "insert", "")) {
            CompletableFuture<ApiResponse> signingIn = CompletableFuture.supplyAsync(
                    () -> AuthChecks.signIn(browser, customer.email(), customer.password(), ip));
            hold.awaitHeld("the sign-in's session INSERT");
            clock.advance(Duration.ofSeconds(1));
            PasswordSteps.confirm(portal(), token, PasswordSteps.NEW_PASSWORD).expectStatus(204);
            hold.release();
            signedIn = finish(signingIn);
        }

        signedIn.expectStatus(204);
        assertError(browser.get("/api/portal/me").send(), 401, "UNAUTHENTICATED");
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
