package io.popdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.auth.OidcFlow.Started;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.CraftedOidcProvider;
import io.popdesk.support.Fixtures.Session;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The second OIDC context (acceptance.md 5절 17): OIDC sign-in on for the console only ({@code OIDC_APPS=console}),
 * against the provider whose ID tokens the test writes ({@link CraftedOidcProvider}). It sees what the test container
 * cannot give: tokens with a broken signature, a discovery document and a token endpoint that fail. "Bare" setup; one
 * context and one database of its own.
 *
 * <p>A sign-in here: {@link #started} (the start's 302 gives {@code state} and {@code nonce}), a token the test
 * writes ({@link #claims}, then {@code provider.rs256} and the like), and {@link #back} (the code is made up and given
 * that token). The clock stands still at the app's time, so tokens carry times from it.
 *
 * <p>Tests share the provider and the app. Each starts with the provider answering normally ({@link #resetProvider});
 * a test that makes the discovery fail moves the clock past the hour the app keeps the document
 * ({@link #forgetDiscovery}) and leaves the provider answering again.
 */
@TestPropertySource(properties = {OidcCraftedAppTest.OWN_CONTEXT,
        "OIDC_CLIENT_ID=" + CraftedOidcProvider.CLIENT_ID,
        "OIDC_CLIENT_SECRET=" + CraftedOidcProvider.CLIENT_SECRET,
        "OIDC_APPS=console"})
public abstract class OidcCraftedAppTest extends BareAppTest {

    /** A property the app does not read; it keeps this context apart from the others. */
    public static final String OWN_CONTEXT = "oidc.test.context=crafted";

    static final String CONSOLE = "/api/console";

    @DynamicPropertySource
    static void craftedProvider(DynamicPropertyRegistry registry) {
        registry.add("OIDC_ISSUER", () -> CraftedOidcProvider.shared().issuer());
    }

    protected final CraftedOidcProvider provider = CraftedOidcProvider.shared();

    /** The start and the callback against this context's provider. */
    protected OidcFlow oidc;

    @BeforeEach
    void resetProvider() {
        provider.reset();
        oidc = new OidcFlow(provider.issuer(), clock);
    }

    /** Starts in this browser and expects the 302 to the provider with the sign-in cookie. */
    protected Started started(ApiClient browser) {
        return oidc.started(browser);
    }

    /**
     * The claims of a token this provider writes for the sign-in {@code started}: {@code iss} of this provider,
     * {@code aud} this client, {@code sub}, the start's {@code nonce}, the verified {@code email}, and the times of
     * the app's clock ({@link OidcFlow#times}). A new map; change what the test is about.
     */
    protected Map<String, Object> claims(Started started, String sub, String email) {
        Map<String, Object> claims = oidc.claims(email);
        claims.put("iss", provider.issuer());
        claims.put("aud", CraftedOidcProvider.CLIENT_ID);
        claims.put("sub", sub);
        claims.put("nonce", started.nonce());
        return claims;
    }

    /** The callback of {@code started} in this browser with a new code that gets {@code idToken}. */
    protected ApiResponse back(ApiClient browser, Started started, String idToken) {
        return oidc.callback(browser, Map.of("code", provider.give(idToken), "state", started.state()));
    }

    /** A whole sign-in of {@code person} in a new console browser with an RS256 token; expects the session. */
    protected void signedIn(Session person) {
        ApiClient browser = console();
        Started started = started(browser);
        String token = provider.rs256(claims(started, OidcFlow.newSub(), person.email()));
        OidcFlow.assertRedirect(browser, back(browser, started, token), "/");
        assertThat(browser.get(CONSOLE + "/me").expect(200).jsonLong("id")).as("account signed in")
                .isEqualTo(person.accountId());
    }

    /**
     * Moves the clock past the hour the app keeps the discovery document (S11-AC-07), so the next start asks for it
     * again.
     */
    protected void forgetDiscovery() {
        clock.advance(Duration.ofHours(1).plusSeconds(1));
    }

    /** The {@code oidc} part of the system status, as the admin. */
    static JsonNode oidcStatus(Session admin) {
        return OidcStatusTest.oidcStatus(admin);
    }
}
