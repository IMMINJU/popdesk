package io.popdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.privacy.PrivacySteps;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Which account an OIDC sign-in reaches. Once linked, only the provider's {@code iss} and {@code sub} count
 * (S11-AC-15). The first time, a verified ASCII email links the account it names (S11-AC-16). Every other case goes
 * back to the same {@code /login?oidc=failed} and links nothing (S11-AC-17).
 */
class OidcMatchTest extends OidcAppTest {

    private static final String CONSOLE = "/api/console";
    private static final String PORTAL = "/api/portal";

    /** What a linked person's later token says instead of their own verified email (S11-AC-15). */
    enum LaterToken {
        ANOTHER_PERSONS_EMAIL,
        AN_EMAIL_NO_ACCOUNT_HAS,
        EMAIL_NOT_VERIFIED,
        NO_EMAIL
    }

    /** An email claim that does not link an account the first time (S11-AC-16). */
    enum Unlinkable {
        VERIFIED_AS_A_STRING,
        VERIFIED_MISSING,
        NOT_VERIFIED,
        NO_EMAIL,
        KELVIN_SIGN_FOR_K
    }

    /** Accounts an OIDC sign-in does not reach (S11-AC-17). */
    enum NoMatch {
        NO_ACCOUNT,
        SUSPENDED,
        ANONYMIZED,
        CANCELLED_INVITATION,
        AGENT_ON_THE_PORTAL,
        CUSTOMER_ON_THE_CONSOLE,
        INVITATION_OVER_SEVEN_DAYS
    }

    @ParameterizedTest
    @EnumSource(LaterToken.class)
    @DisplayName("S11-AC-15 once linked, a token with the same sub signs in to the linked account with another "
            + "person's email, an email no account has, email_verified false, or no email")
    void linkedSubjectAloneMatches(LaterToken later) {
        Session admin = fixtures.signInAdmin();
        Session agent = person(admin, Role.AGENT);
        Session other = person(admin, Role.AGENT);
        String sub = OidcFlow.newSub();
        assertThat(oidc.signedIn(console(), CONSOLE, sub, oidc.claims(agent.email()))).as("first sign-in")
                .isEqualTo(agent.accountId());
        Map<String, Object> claims = oidc.claims(agent.email());
        switch (later) {
            case ANOTHER_PERSONS_EMAIL -> claims.put("email", other.email());
            case AN_EMAIL_NO_ACCOUNT_HAS -> claims.put("email", Unique.email("nobody"));
            case EMAIL_NOT_VERIFIED -> claims.put("email_verified", false);
            case NO_EMAIL -> {
                claims.remove("email");
                claims.remove("email_verified");
            }
        }

        long signedIn = oidc.signedIn(console(), CONSOLE, sub, claims);

        assertThat(signedIn).as("account of the later sign-in").isEqualTo(agent.accountId());
        assertThat(linked(admin, other.accountId())).as("oidcLinked of the other person").isFalse();
    }

    @Test
    @DisplayName("S11-AC-16 the first sign-in with a verified email in other letter case and with spaces around it "
            + "links that account, and a later sign-in with the same sub needs no email")
    void verifiedEmailLinksIgnoringCaseAndSpaces() {
        Session admin = fixtures.signInAdmin();
        Session customer = person(admin, Role.CUSTOMER);
        String sub = OidcFlow.newSub();
        String written = "  " + customer.email().toUpperCase(Locale.ROOT) + " ";

        long first = oidc.signedIn(portal(), PORTAL, sub, oidc.claims(written));

        assertThat(first).as("account of the first sign-in").isEqualTo(customer.accountId());
        assertThat(linked(admin, customer.accountId())).as("oidcLinked after the first sign-in").isTrue();
        assertThat(oidc.signedIn(portal(), PORTAL, sub, oidc.times())).as("account of a sign-in without email")
                .isEqualTo(customer.accountId());
    }

    @ParameterizedTest
    @EnumSource(Unlinkable.class)
    @DisplayName("S11-AC-16, S11-AC-17 the first time, email_verified as the string \"true\", missing, or false, no "
            + "email, or an email with the Kelvin sign for K goes back to /login?oidc=failed and links nothing, and "
            + "a verified email then links the account")
    void unverifiedOrNonAsciiEmailDoesNotLink(Unlinkable claim) {
        Session admin = fixtures.signInAdmin();
        String kate = Unique.email("kate");
        Session agent = fixtures.accept(fixtures.invite(admin, Role.AGENT, kate, Unique.text("Kate"), List.of()));
        Map<String, Object> claims = oidc.claims(agent.email());
        switch (claim) {
            case VERIFIED_AS_A_STRING -> claims.put("email_verified", "true");
            case VERIFIED_MISSING -> claims.remove("email_verified");
            case NOT_VERIFIED -> claims.put("email_verified", false);
            case NO_EMAIL -> claims.remove("email");
            case KELVIN_SIGN_FOR_K -> claims.put("email", "K" + agent.email().substring(1));
        }
        assertThat(agent.email()).as("an agent email that starts with k").startsWith("k");
        String sub = OidcFlow.newSub();
        ApiClient browser = console();

        ApiResponse back = oidc.signIn(browser, sub, claims);

        OidcFlow.assertBackAtLogin(browser, back, "failed");
        OidcFlow.assertNoSession(browser, CONSOLE);
        assertThat(linked(admin, agent.accountId())).as("oidcLinked after the failed sign-in").isFalse();
        assertThat(oidc.signedIn(console(), CONSOLE, OidcFlow.newSub(), oidc.claims(agent.email())))
                .as("a later sign-in with another sub and the verified email").isEqualTo(agent.accountId());
    }

    @Test
    @DisplayName("S11-AC-16, S11-AC-17 an account linked to one sub is not matched by its email for another sub of "
            + "the same issuer, and the first sub still signs in")
    void secondSubjectForTheSameAccountFails() {
        Session admin = fixtures.signInAdmin();
        Session agent = person(admin, Role.AGENT);
        String first = OidcFlow.newSub();
        oidc.signedIn(console(), CONSOLE, first, oidc.claims(agent.email()));
        ApiClient browser = console();

        ApiResponse second = oidc.signIn(browser, OidcFlow.newSub(), oidc.claims(agent.email()));

        OidcFlow.assertBackAtLogin(browser, second, "failed");
        OidcFlow.assertNoSession(browser, CONSOLE);
        assertThat(oidc.signedIn(console(), CONSOLE, first, oidc.claims(agent.email()))).as("the first sub again")
                .isEqualTo(agent.accountId());
    }

    @ParameterizedTest
    @EnumSource(NoMatch.class)
    @DisplayName("S11-AC-17 no account, a suspended, anonymized, or cancelled one, an agent on the portal, a "
            + "customer on the console, and an invitation over 7 days old all get the same 302 to "
            + "/login?oidc=failed as an unknown email, without a session and without a link")
    void everyNoMatchLooksTheSame(NoMatch noMatch) {
        Session admin = fixtures.signInAdmin();
        ApiClient unknownBrowser = console();
        ApiResponse unknown = oidc.signIn(unknownBrowser, OidcFlow.newSub(), oidc.claims(Unique.email("nobody")));
        OidcFlow.assertBackAtLogin(unknownBrowser, unknown, "failed");
        String sub = OidcFlow.newSub();
        long accountId;
        String email;
        boolean onPortal = false;
        switch (noMatch) {
            case NO_ACCOUNT -> {
                accountId = -1;
                email = Unique.email("nobody");
            }
            case SUSPENDED -> {
                Session agent = person(admin, Role.AGENT);
                admin.client().post(ACCOUNTS + "/" + agent.accountId() + "/suspend").expect(200);
                accountId = agent.accountId();
                email = agent.email();
            }
            case ANONYMIZED -> {
                Session agent = person(admin, Role.AGENT);
                oidc.signedIn(console(), CONSOLE, sub, oidc.claims(agent.email()));
                PrivacySteps.anonymize(admin, agent.accountId());
                accountId = agent.accountId();
                email = agent.email();
            }
            case CANCELLED_INVITATION -> {
                Invitation invited = fixtures.invite(admin, Role.AGENT);
                admin.client().post("/api/console/invitations/" + invited.accountId() + "/cancel").expect(200);
                accountId = invited.accountId();
                email = invited.email();
            }
            case AGENT_ON_THE_PORTAL -> {
                Session agent = person(admin, Role.AGENT);
                onPortal = true;
                accountId = agent.accountId();
                email = agent.email();
            }
            case CUSTOMER_ON_THE_CONSOLE -> {
                Session customer = person(admin, Role.CUSTOMER);
                accountId = customer.accountId();
                email = customer.email();
            }
            case INVITATION_OVER_SEVEN_DAYS -> {
                Invitation invited = fixtures.invite(admin, Role.AGENT);
                clock.advance(Duration.ofDays(7).plusMinutes(1));
                accountId = invited.accountId();
                email = invited.email();
            }
            default -> throw new IllegalStateException(noMatch.name());
        }
        ApiClient browser = onPortal ? portal() : console();

        ApiResponse back = oidc.signIn(browser, sub, oidc.claims(email));

        OidcFlow.assertBackAtLogin(browser, back, "failed");
        assertThat(back.body()).as("body of %s next to the unknown email's", back).isEqualTo(unknown.body());
        OidcFlow.assertNoSession(browser, onPortal ? PORTAL : CONSOLE);
        if (accountId > 0) {
            Session signedInAdmin = fixtures.signInAdmin();
            assertThat(linked(signedInAdmin, accountId)).as("oidcLinked of the account after the sign-in").isFalse();
        }
    }
}
