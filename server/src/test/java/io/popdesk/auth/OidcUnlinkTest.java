package io.popdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.privacy.PrivacySteps;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The admin removes an account's OIDC link (S11-AC-27): 204, {@code oidcLinked} false, every session of the account
 * ended (one from OIDC and one from the password), and {@code OIDC_UNLINKED} on the account by the admin. Without a
 * link it is 404. The next OIDC sign-in links the account again by its verified email, with another sub too.
 */
class OidcUnlinkTest extends OidcAppTest {

    private static final String CONSOLE = "/api/console";

    @Test
    @DisplayName("S11-AC-27 unlinking answers 204, makes oidcLinked false, ends the account's OIDC and password "
            + "sessions (401 on /me), and adds one OIDC_UNLINKED on the account by the admin")
    void unlinkEndsTheLinkAndTheSessions() {
        Session admin = fixtures.signInAdmin();
        Session agent = person(admin, Role.AGENT);
        ApiClient oidcBrowser = console();
        oidc.signedIn(oidcBrowser, CONSOLE, OidcFlow.newSub(), oidc.claims(agent.email()));
        agent.client().get(CONSOLE + "/me").expect(200);

        ApiResponse answer = unlink(admin.client(), agent.accountId()).send();

        assertThat(answer.status()).as("%s: %s", answer, answer.snippet()).isEqualTo(204);
        assertThat(linked(admin, agent.accountId())).as("oidcLinked after unlinking").isFalse();
        assertThat(oidcBrowser.get(CONSOLE + "/me").send().status()).as("/me in the OIDC session").isEqualTo(401);
        assertThat(agent.client().get(CONSOLE + "/me").send().status()).as("/me in the password session")
                .isEqualTo(401);
        List<JsonNode> unlinked = PrivacySteps.entries(admin, "action", "OIDC_UNLINKED", "targetAccountId",
                agent.accountId());
        assertThat(unlinked).as("OIDC_UNLINKED entries on the account").hasSize(1);
        JsonNode entry = unlinked.getFirst();
        assertThat(entry.path("target").path("type").asString("")).as("target of %s", entry).isEqualTo("ACCOUNT");
        assertThat(entry.path("target").path("accountId").asLong(-1)).as("target of %s", entry)
                .isEqualTo(agent.accountId());
        assertThat(entry.path("actor").path("accountId").asLong(-1)).as("actor of %s", entry)
                .isEqualTo(admin.accountId());
    }

    @Test
    @DisplayName("S11-AC-27 unlinking an account that was never linked, or a second time, is 404 NOT_FOUND and adds "
            + "no OIDC_UNLINKED, while the first unlinking of a linked account answers 204")
    void unlinkWithoutALinkIs404() {
        Session admin = fixtures.signInAdmin();
        Session never = person(admin, Role.AGENT);
        Session agent = person(admin, Role.AGENT);
        linkByOidc(agent);

        ApiResponse neverLinked = unlink(admin.client(), never.accountId()).send();
        ApiResponse first = unlink(admin.client(), agent.accountId()).send();
        ApiResponse second = unlink(admin.client(), agent.accountId()).send();

        assertThat(neverLinked.status()).as("%s: %s", neverLinked, neverLinked.snippet()).isEqualTo(404);
        assertThat(neverLinked.errorCode()).as("code of %s", neverLinked.snippet()).isEqualTo("NOT_FOUND");
        assertThat(first.status()).as("%s: %s", first, first.snippet()).isEqualTo(204);
        assertThat(second.status()).as("%s: %s", second, second.snippet()).isEqualTo(404);
        assertThat(second.errorCode()).as("code of %s", second.snippet()).isEqualTo("NOT_FOUND");
        assertThat(PrivacySteps.entries(admin, "action", "OIDC_UNLINKED", "targetAccountId", never.accountId()))
                .as("OIDC_UNLINKED entries on the account never linked").isEmpty();
        assertThat(PrivacySteps.entries(admin, "action", "OIDC_UNLINKED", "targetAccountId", agent.accountId()))
                .as("OIDC_UNLINKED entries on the account unlinked once").hasSize(1);
    }

    @Test
    @DisplayName("S11-AC-27 after unlinking, an OIDC sign-in with another sub and the same verified email links the "
            + "account again, and the old sub then goes back to /login?oidc=failed")
    void anotherSubLinksAgainAfterUnlinking() {
        Session admin = fixtures.signInAdmin();
        Session agent = person(admin, Role.AGENT);
        String old = linkByOidc(agent);
        assertThat(unlink(admin.client(), agent.accountId()).send().status()).as("unlinking").isEqualTo(204);
        String renewed = OidcFlow.newSub();

        long signedIn = oidc.signedIn(console(), CONSOLE, renewed, oidc.claims(agent.email()));

        assertThat(signedIn).as("account of the sign-in with the new sub").isEqualTo(agent.accountId());
        assertThat(linked(admin, agent.accountId())).as("oidcLinked after the new sign-in").isTrue();
        ApiClient browser = console();
        OidcFlow.assertBackAtLogin(browser, oidc.signIn(browser, old, oidc.claims(agent.email())), "failed");
        OidcFlow.assertNoSession(browser, CONSOLE);
        assertThat(oidc.signedIn(console(), CONSOLE, renewed, oidc.times())).as("the new sub without an email")
                .isEqualTo(agent.accountId());
    }
}
