package io.popdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.auth.OidcFlow.Started;
import io.popdesk.support.ApiClient;
import io.popdesk.support.AppLog;
import io.popdesk.support.CraftedOidcProvider;
import io.popdesk.support.CraftedOidcProvider.TokenRequest;
import io.popdesk.support.Fixtures.Session;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the app logs when an OIDC sign-in fails (S11-AC-25): an ID token with a broken signature, a token endpoint
 * that refuses the code, a callback with a state the app never gave, and an error from the provider not of
 * {@code [a-z_]}. The test waits for the line of a cancel with {@code access_denied} last, so the lines of the failures
 * before it were written, then looks for the values of the sign-ins in what was written.
 */
class OidcCraftedLogTest extends OidcCraftedAppTest {

    @Test
    @DisplayName("S11-AC-25 when an OIDC sign-in fails for its signature, its code at the token endpoint, or its "
            + "state, or the provider sends an error not of [a-z_], the log holds none of the code, state, nonce, PKCE "
            + "challenge and verifier, sign-in cookie, ID token, sub, whole email, client secret, or that error")
    void logOfFailedSignInsHoldsNoSecret() {
        Session admin = fixtures.signInAdmin();
        Session agent = fixtures.agent(admin);
        String sub = OidcFlow.newSub();
        String badError = "EVIL-ERROR-" + sub;
        List<Started> starts = new ArrayList<>();
        List<String> secrets = new ArrayList<>();
        AppLog.Mark mark = log.mark();

        ApiClient unsignedBrowser = console();
        Started unsigned = started(unsignedBrowser);
        String unsignedToken = CraftedOidcProvider.unsigned(claims(unsigned, sub, agent.email()));
        String unsignedCode = provider.give(unsignedToken);
        OidcFlow.assertBackAtLogin(unsignedBrowser, oidc.callback(unsignedBrowser,
                Map.of("code", unsignedCode, "state", unsigned.state())), "failed");
        starts.add(unsigned);
        secrets.addAll(List.of(unsignedToken, unsignedCode));

        ApiClient refusedBrowser = console();
        Started refused = started(refusedBrowser);
        String refusedToken = provider.rs256(claims(refused, sub, agent.email()));
        String refusedCode = provider.give(refusedToken);
        provider.tokenAnswers(400);
        OidcFlow.assertBackAtLogin(refusedBrowser, oidc.callback(refusedBrowser,
                Map.of("code", refusedCode, "state", refused.state())), "failed");
        provider.reset();
        starts.add(refused);
        secrets.addAll(List.of(refusedToken, refusedCode));

        ApiClient wrongStateBrowser = console();
        Started wrongState = started(wrongStateBrowser);
        String wrongStateCode = provider.give(provider.rs256(claims(wrongState, sub, agent.email())));
        OidcFlow.assertBackAtLogin(wrongStateBrowser, oidc.callback(wrongStateBrowser,
                Map.of("code", wrongStateCode, "state", PasswordSteps.tokenNeverIssued())), "failed");
        starts.add(wrongState);
        secrets.add(wrongStateCode);

        ApiClient badErrorBrowser = console();
        Started badCancel = started(badErrorBrowser);
        OidcFlow.assertRedirect(badErrorBrowser, oidc.callback(badErrorBrowser,
                Map.of("error", badError, "state", badCancel.state())), "/login");
        starts.add(badCancel);

        ApiClient cancelBrowser = console();
        Started cancel = started(cancelBrowser);
        OidcFlow.assertRedirect(cancelBrowser, oidc.callback(cancelBrowser,
                Map.of("error", "access_denied", "state", cancel.state())), "/login");
        starts.add(cancel);

        log.awaitLine(mark, "access_denied");
        String written = log.since(mark);
        for (String code : List.of(unsignedCode, refusedCode)) {
            List<TokenRequest> asked = provider.tokenRequests(code);
            assertThat(asked).as("token requests of %s", code).isNotEmpty();
            asked.forEach(r -> secrets.add(r.codeVerifier()));
        }
        for (Started s : starts) {
            secrets.addAll(List.of(s.state(), s.nonce(), s.query().get("code_challenge"),
                    AuthChecks.cookieValue(s.response().setCookie(OidcFlow.COOKIE))));
        }
        secrets.addAll(List.of(sub, agent.email(), CraftedOidcProvider.CLIENT_SECRET, badError));
        for (String secret : secrets) {
            assertThat(secret).as("a value of the sign-ins").isNotBlank();
            assertThat(written).as("the log of the failed sign-ins").doesNotContain(secret);
        }
    }
}
