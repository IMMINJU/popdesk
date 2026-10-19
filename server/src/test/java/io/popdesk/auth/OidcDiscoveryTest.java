package io.popdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.CraftedOidcProvider.Endpoint;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The discovery document failing (S11-AC-07): the start goes back to the sign-in page, and for 30 seconds of the
 * app's clock the next starts do not ask the provider again. The test first moves the clock past the hour the app
 * keeps a document it got ({@link #forgetDiscovery}) and leaves the provider answering and the 30 seconds over.
 */
class OidcDiscoveryTest extends OidcCraftedAppTest {

    @Test
    @DisplayName("S11-AC-07 a discovery document answering 500 sends the start back to /login?oidc=unavailable without "
            + "the sign-in cookie and with no-store; a start 29 seconds later does the same without a request to the "
            + "provider, and a start 31 seconds after the failure asks the provider again")
    void failedDiscoveryIsKeptThirtySeconds() {
        forgetDiscovery();
        provider.discoveryAnswers(500);
        try {
            int before = provider.requests(Endpoint.DISCOVERY);
            ApiClient browser = console();

            ApiResponse failed = oidc.start(browser);

            OidcFlow.assertBackAtLogin(browser, failed, "unavailable");
            assertThat(failed.setCookie(OidcFlow.COOKIE)).as("sign-in cookie of %s", failed).isNull();
            assertThat(failed.header("Cache-Control")).as("Cache-Control of %s", failed).contains("no-store");
            int afterFirst = provider.requests(Endpoint.DISCOVERY);
            assertThat(afterFirst - before).as("discovery requests of the first start").isPositive();
            clock.advance(Duration.ofSeconds(29));
            ApiClient second = console();
            OidcFlow.assertBackAtLogin(second, oidc.start(second), "unavailable");
            assertThat(provider.requests(Endpoint.DISCOVERY)).as("discovery requests after a start 29 seconds later")
                    .isEqualTo(afterFirst);
            clock.advance(Duration.ofSeconds(2));
            provider.reset();
            oidc.started(console());
            assertThat(provider.requests(Endpoint.DISCOVERY)).as("discovery requests after a start 31 seconds later")
                    .isGreaterThan(afterFirst);
        } finally {
            provider.reset();
            clock.advance(Duration.ofSeconds(31));
        }
    }
}
