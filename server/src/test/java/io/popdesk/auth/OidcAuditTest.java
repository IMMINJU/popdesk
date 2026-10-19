package io.popdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.auth.OidcFlow.Started;
import io.popdesk.support.ApiClient;
import io.popdesk.support.AppLog;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.OidcProvider;
import io.popdesk.support.OidcProvider.Redirect;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * The audit entries of an OIDC sign-in (S11-AC-24) and what the app logs during one (S11-AC-25). Entries are read
 * for the accounts the test made ({@link #auditOf}); "new" ones are those with a {@code seq} above the account's
 * newest entry before the sign-in.
 */
class OidcAuditTest extends OidcAppTest {

    private static final String CONSOLE = "/api/console";

    /** An account the sign-in matches but does not let in (S11-AC-17). */
    enum Refused {
        SUSPENDED,
        CUSTOMER_ON_THE_CONSOLE
    }

    @Test
    @DisplayName("S11-AC-24 the first OIDC sign-in of an invited account adds OIDC_LINKED with the issuer, then "
            + "INVITATION_ACCEPTED and SIGN_IN_SUCCEEDED with {method: OIDC}, each on the account, and a second "
            + "sign-in adds only SIGN_IN_SUCCEEDED; no entry holds the sub")
    void firstSignInOfAnInvitedAccount() {
        Session admin = fixtures.signInAdmin();
        Invitation invited = fixtures.invite(admin, Role.AGENT);
        String sub = OidcFlow.newSub();
        long before = newestSeq(admin, invited.accountId());

        oidc.signedIn(console(), CONSOLE, sub, oidc.claims(invited.email()));

        List<JsonNode> first = after(admin, invited.accountId(), before);
        assertThat(actions(first)).as("new entries of the account").containsExactly("OIDC_LINKED",
                "INVITATION_ACCEPTED", "SIGN_IN_SUCCEEDED");
        assertLinked(first.get(0), invited.accountId());
        assertOidcEntry(first.get(1), invited.accountId());
        assertOidcEntry(first.get(2), invited.accountId());
        long between = newestSeq(admin, invited.accountId());
        oidc.signedIn(console(), CONSOLE, sub, oidc.claims(invited.email()));
        List<JsonNode> second = after(admin, invited.accountId(), between);
        assertThat(actions(second)).as("new entries of the second sign-in").containsExactly("SIGN_IN_SUCCEEDED");
        assertOidcEntry(second.getFirst(), invited.accountId());
        assertThat(auditOf(admin, invited.accountId()).toString()).as("the account's entries").doesNotContain(sub);
    }

    @Test
    @DisplayName("S11-AC-24 the first OIDC sign-in of an active account adds OIDC_LINKED and SIGN_IN_SUCCEEDED, "
            + "without INVITATION_ACCEPTED")
    void firstSignInOfAnActiveAccount() {
        Session admin = fixtures.signInAdmin();
        Session agent = person(admin, Role.AGENT);
        String sub = OidcFlow.newSub();
        long before = newestSeq(admin, agent.accountId());

        oidc.signedIn(console(), CONSOLE, sub, oidc.claims(agent.email()));

        List<JsonNode> added = after(admin, agent.accountId(), before);
        assertThat(actions(added)).as("new entries of the account").containsExactly("OIDC_LINKED",
                "SIGN_IN_SUCCEEDED");
        assertLinked(added.get(0), agent.accountId());
        assertOidcEntry(added.get(1), agent.accountId());
        assertThat(added.toString()).as("the new entries").doesNotContain(sub);
    }

    @ParameterizedTest
    @EnumSource(Refused.class)
    @DisplayName("S11-AC-24 an OIDC sign-in that matches an account but goes to /login?oidc=failed adds "
            + "SIGN_IN_FAILED with {method: OIDC} on the account, and no OIDC_LINKED")
    void refusedSignInIsAudited(Refused refused) {
        Session admin = fixtures.signInAdmin();
        Session person = person(admin, refused == Refused.SUSPENDED ? Role.AGENT : Role.CUSTOMER);
        if (refused == Refused.SUSPENDED) {
            admin.client().post(ACCOUNTS + "/" + person.accountId() + "/suspend").expect(200);
        }
        String sub = OidcFlow.newSub();
        long before = newestSeq(admin, person.accountId());
        ApiClient browser = console();

        OidcFlow.assertBackAtLogin(browser, oidc.signIn(browser, sub, oidc.claims(person.email())), "failed");

        List<JsonNode> added = after(admin, person.accountId(), before);
        assertThat(actions(added)).as("new entries of the account").containsExactly("SIGN_IN_FAILED");
        assertOidcEntry(added.getFirst(), person.accountId());
        assertThat(added.toString()).as("the new entries").doesNotContain(sub);
    }

    @Test
    @DisplayName("S11-AC-25 during an OIDC sign-in and a cancel the log holds the provider's error code, and none of "
            + "the code, state, nonce, PKCE challenge, sign-in cookie, sub, whole email, client secret, "
            + "error_description, or an error not of [a-z_]")
    void logHoldsNoSecretOfTheSignIn() {
        Session admin = fixtures.signInAdmin();
        Invitation invited = fixtures.invite(admin, Role.AGENT);
        String sub = OidcFlow.newSub();
        String description = "rst-oidc-description-" + sub;
        String badError = "EVIL-ERROR-" + sub;
        ApiClient browser = console();
        ApiClient cancelling = console();
        ApiClient badCancel = console();
        AppLog.Mark mark = log.mark();

        Started started = oidc.started(browser);
        Redirect back = oidc.logIn(started, sub, oidc.claims(invited.email()));
        OidcFlow.assertRedirect(browser, oidc.callback(browser, back), "/");
        Started cancelled = oidc.started(cancelling);
        OidcFlow.assertRedirect(cancelling, oidc.callback(cancelling, Map.of("error", "access_denied",
                "error_description", description, "state", cancelled.state())), "/login");
        Started bad = oidc.started(badCancel);
        OidcFlow.assertRedirect(badCancel, oidc.callback(badCancel, Map.of("error", badError,
                "state", bad.state())), "/login");

        log.awaitLine(mark, "access_denied");
        String written = log.since(mark);
        for (Started s : List.of(started, cancelled, bad)) {
            assertThat(written).as("the log of the sign-in").doesNotContain(s.state()).doesNotContain(s.nonce())
                    .doesNotContain(s.query().get("code_challenge"))
                    .doesNotContain(AuthChecks.cookieValue(s.response().setCookie(OidcFlow.COOKIE)));
        }
        assertThat(written).as("the log of the sign-in").doesNotContain(back.code()).doesNotContain(sub)
                .doesNotContain(invited.email()).doesNotContain(OidcProvider.CLIENT_SECRET)
                .doesNotContain(description).doesNotContain(badError);
    }

    private long newestSeq(Session admin, long accountId) {
        return auditOf(admin, accountId).stream().mapToLong(e -> e.path("seq").asLong()).max().orElse(0);
    }

    private List<JsonNode> after(Session admin, long accountId, long seq) {
        return auditOf(admin, accountId).stream().filter(e -> e.path("seq").asLong() > seq).toList();
    }

    private static void assertOnAccount(JsonNode entry, long accountId) {
        JsonNode target = entry.path("target");
        assertThat(target.path("type").asString("")).as("target type of %s", entry).isEqualTo("ACCOUNT");
        assertThat(target.path("accountId").asLong(-1)).as("target account of %s", entry).isEqualTo(accountId);
    }

    /** An entry on the account whose changes are exactly {@code {"method": "OIDC"}}. */
    private static void assertOidcEntry(JsonNode entry, long accountId) {
        assertOnAccount(entry, accountId);
        JsonNode changes = entry.path("changes");
        assertThat(OidcFlow.fieldNames(changes)).as("changes of %s", entry).isEqualTo(Set.of("method"));
        assertThat(changes.path("method").asString("")).as("method of %s", entry).isEqualTo("OIDC");
    }

    /** {@code OIDC_LINKED} on the account with the issuer in its changes. */
    private void assertLinked(JsonNode entry, long accountId) {
        assertOnAccount(entry, accountId);
        assertThat(entry.path("changes").path("issuer").asString("")).as("issuer in %s", entry)
                .isEqualTo(oidc.provider().issuer());
    }
}
