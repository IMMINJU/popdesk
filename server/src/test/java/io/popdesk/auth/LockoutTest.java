package io.popdesk.auth;

import static io.popdesk.auth.AuthChecks.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.AppTest;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.App;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Five wrong passwords in a row lock the account for 15 minutes; a success resets the count. */
class LockoutTest extends BareAppTest {

    @Test
    @DisplayName("S0-AC-23 five wrong passwords in a row lock the account for 15 minutes, "
            + "and the right password gets the same 401 meanwhile")
    void fiveWrongPasswordsLockForFifteenMinutes() {
        Session customer = newCustomer();
        String ip = AuthChecks.freshIp();
        ApiClient browser = portal();
        ApiResponse unknown = AuthChecks.signIn(browser, Unique.email("nobody"), customer.password(), ip);
        assertError(unknown, 401, "SIGN_IN_FAILED");

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertError(AuthChecks.signIn(browser, customer.email(), "wrong-Password-" + attempt, ip),
                    401, "SIGN_IN_FAILED");
        }
        ApiResponse locked = AuthChecks.signIn(browser, customer.email(), customer.password(), ip);
        clock.advance(Duration.ofMinutes(14));
        ApiResponse stillLocked = AuthChecks.signIn(browser, customer.email(), customer.password(), ip);

        assertError(locked, 401, "SIGN_IN_FAILED");
        assertThat(locked.body()).as("locked body vs unknown email body").isEqualTo(unknown.body());
        assertError(stillLocked, 401, "SIGN_IN_FAILED");
        assertError(browser.get("/api/portal/me").send(), 401, "UNAUTHENTICATED");

        clock.advance(Duration.ofMinutes(2));
        AuthChecks.signIn(browser, customer.email(), customer.password(), ip).expectStatus(204);
        assertThat(browser.get("/api/portal/me").expect(200).jsonLong("id")).isEqualTo(customer.accountId());
    }

    @Test
    @DisplayName("S0-AC-23 a successful sign-in resets the count of wrong passwords")
    void successResetsTheCount() {
        Session customer = newCustomer();
        String ip = AuthChecks.freshIp();
        ApiClient browser = portal();

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertError(AuthChecks.signIn(browser, customer.email(), "wrong-Password-" + attempt, ip),
                    401, "SIGN_IN_FAILED");
        }
        AuthChecks.signIn(browser, customer.email(), customer.password(), ip).expectStatus(204);
        for (int attempt = 5; attempt <= 8; attempt++) {
            assertError(AuthChecks.signIn(browser, customer.email(), "wrong-Password-" + attempt, ip),
                    401, "SIGN_IN_FAILED");
        }

        ApiResponse afterEight = AuthChecks.signIn(portal(), customer.email(), customer.password(), ip);

        afterEight.expectStatus(204);
    }

    private Session newCustomer() {
        Session admin = AuthChecks.signedIn(console(), App.CONSOLE, AppTest.ADMIN_EMAIL, AppTest.ADMIN_PASSWORD);
        return fixtures.customer(admin, fixtures.createCompany(admin).code());
    }
}
