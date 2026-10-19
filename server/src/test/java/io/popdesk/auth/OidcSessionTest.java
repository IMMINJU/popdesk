package io.popdesk.auth;

import static io.popdesk.auth.AuthChecks.assertError;

import io.popdesk.support.ApiClient;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A session started by OIDC ends as one started with a password does (S11-AC-21, S0-AC-26, S1A-AC-02): when the
 * account is suspended, its role changes, or its password changes in another session, and at sign-out.
 */
class OidcSessionTest extends OidcAppTest {

    /** What ends the OIDC session. */
    enum Ending {
        SUSPENDED,
        ROLE_CHANGED,
        PASSWORD_CHANGED,
        SIGNED_OUT
    }

    @ParameterizedTest
    @EnumSource(Ending.class)
    @DisplayName("S11-AC-21 a session started by OIDC gets 401 after the account is suspended, its role changes, its "
            + "password changes in its password session, or it signs out")
    void oidcSessionEndsLikeAPasswordSession(Ending ending) {
        Session admin = fixtures.signInAdmin();
        Session agent = person(admin, Role.AGENT);
        ApiClient browser = console();
        oidc.signedIn(browser, "/api/console", OidcFlow.newSub(), oidc.claims(agent.email()));
        browser.get("/api/console/me").expect(200);

        switch (ending) {
            case SUSPENDED -> admin.client().post(ACCOUNTS + "/" + agent.accountId() + "/suspend").expect(200);
            case ROLE_CHANGED -> admin.client().put(ACCOUNTS + "/" + agent.accountId() + "/role")
                    .json(Map.of("role", "ADMIN")).expect(200);
            case PASSWORD_CHANGED -> agent.client().put("/api/console/me/password")
                    .json(Map.of("currentPassword", agent.password(), "newPassword", PasswordSteps.NEW_PASSWORD))
                    .expect(204);
            case SIGNED_OUT -> browser.post("/api/public/sign-out").expect(204);
        }

        assertError(browser.get("/api/console/me").send(), 401, "UNAUTHENTICATED");
    }
}
