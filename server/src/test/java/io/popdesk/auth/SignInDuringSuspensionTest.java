package io.popdesk.auth;

import static io.popdesk.auth.AuthChecks.assertError;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.HeldWrite;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A sign-in that began before its account was suspended and ends after it. Ending the account's
 * sessions cannot catch it, because its session is stored only when the sign-in ends; the session
 * must still get 401.
 *
 * <p>The order is made in the database ({@link HeldWrite}), as in {@link SignInDuringPasswordChangeTest}:
 * a trigger keeps the INSERT of the new session row waiting until the test lets it go on. By then the
 * sign-in has checked the account. The admin is signed in before, so the suspension writes no new
 * session row.
 */
class SignInDuringSuspensionTest extends BareAppTest {

    @Autowired
    PostgreSQLContainer postgres;

    @Test
    @DisplayName("S1A-AC-01 a sign-in that began before the account was suspended and ended after it leaves a "
            + "session that gets 401")
    void sessionOfASignInFromBeforeTheSuspensionIsRefused() {
        Session admin = fixtures.signInAdmin();
        Session customer = fixtures.customer(admin, fixtures.createCompany(admin).code());
        ApiClient browser = portal();
        browser.csrfToken();
        String ip = AuthChecks.freshIp();
        ApiResponse signedIn;

        try (HeldWrite hold = new HeldWrite(db, postgres, "spring_session", "insert", "")) {
            CompletableFuture<ApiResponse> signingIn = CompletableFuture.supplyAsync(
                    () -> AuthChecks.signIn(browser, customer.email(), customer.password(), ip));
            hold.awaitHeld("the sign-in's session INSERT");
            admin.client().post("/api/console/accounts/" + customer.accountId() + "/suspend").expect(200);
            hold.release();
            signedIn = signingIn.orTimeout(30, TimeUnit.SECONDS).join();
        }

        signedIn.expectStatus(204);
        assertError(browser.get("/api/portal/me").send(), 401, "UNAUTHENTICATED");
    }
}
