package io.popdesk.auth;

import static io.popdesk.auth.AuthChecks.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.App;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Asking for a password reset (AUTH-7): the answer never tells whether a mail goes out, the mail
 * goes only to an {@code ACTIVE} account of the app of the host, and its link is built from the
 * configured address.
 */
class PasswordResetRequestTest extends ConnectedAppTest {

    /** Requests that must send no mail and must look like one that does. */
    enum Silent {
        UNKNOWN_EMAIL,
        SUSPENDED_ACCOUNT,
        INVITATION_NOT_ACCEPTED,
        CUSTOMER_ON_THE_CONSOLE,
        AGENT_ON_THE_PORTAL
    }

    /** Who asks where: the host, the email that must get nothing, and an account of that host that gets a mail. */
    private record Asked(App host, String email, Session known) {}

    /** Headers of the request that a link must not be built from. */
    enum Misleading {
        HOST_BEHIND_A_PROXY {
            @Override
            ApiRequest on(ApiRequest request) {
                return request.header("Host", "app-" + Unique.number() + ".internal")
                        .header("X-Forwarded-Host", PORTAL_HOST);
            }
        },
        FORWARDED_HOST_WITH_PORT_AND_HTTPS {
            @Override
            ApiRequest on(ApiRequest request) {
                return request.header("X-Forwarded-Host", PORTAL_HOST + ":8443")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Port", "8443");
            }
        },
        HOST_WITH_PORT {
            @Override
            ApiRequest on(ApiRequest request) {
                return request.header("Host", PORTAL_HOST + ":8443");
            }
        };

        abstract ApiRequest on(ApiRequest request);
    }

    @ParameterizedTest
    @EnumSource(Silent.class)
    @DisplayName("S1A-AC-05, S1A-AC-78 a request for an address that gets no mail has the same status and body, "
            + "byte for byte, as one for an active account of that app")
    void answerDoesNotTellWhetherAMailGoesOut(Silent which) {
        Asked asked = arrange(which);

        ApiResponse known = PasswordSteps.request(client(asked.host()), asked.known().email());
        ApiResponse silent = PasswordSteps.request(client(asked.host()), asked.email());

        known.expectStatus(202);
        silent.expectStatus(202);
        assertThat(silent.body()).as("body for %s vs body for an active account", which).isEqualTo(known.body());
        assertThat(silent.header("Content-Type")).as("Content-Type").isEqualTo(known.header("Content-Type"));
        assertThat(silent.header("Content-Length")).as("Content-Length").isEqualTo(known.header("Content-Length"));
    }

    @ParameterizedTest
    @EnumSource(Silent.class)
    @DisplayName("S1A-AC-07 no reset mail goes to an address that is not an active account of the host's app")
    void noMailUnlessActiveAccountOfThatApp(Silent which) {
        Asked asked = arrange(which);

        PasswordSteps.request(client(asked.host()), asked.email()).expectStatus(202);
        PasswordSteps.request(client(asked.host()), asked.known().email()).expectStatus(202);

        // The send queue is in order: once the later mail arrived, the earlier one would have too.
        PasswordSteps.awaitMails(mail(), asked.known().email(), 1);
        Wait.holds("no reset mail to " + asked.email(), Duration.ofSeconds(2),
                () -> PasswordSteps.mails(mail(), asked.email()).isEmpty());
    }

    @Test
    @DisplayName("S1A-AC-06 an active account gets a mail in its language with a link on the host of its own app")
    void mailLinksToTheAccountsApp() {
        Session admin = fixtures.signInAdmin();
        Session customer = fixtures.customer(admin, fixtures.createCompany(admin).code());
        Session agent = fixtures.agent(admin);

        PasswordSteps.request(portal(), customer.email()).expectStatus(202);
        PasswordSteps.request(console(), agent.email()).expectStatus(202);

        ReceivedMail toCustomer = PasswordSteps.awaitMails(mail(), customer.email(), 1).getFirst();
        ReceivedMail toAgent = PasswordSteps.awaitMails(mail(), agent.email(), 1).getFirst();
        String customerLink = PasswordSteps.linkIn(toCustomer.text());
        String agentLink = PasswordSteps.linkIn(toAgent.text());
        assertThat(customerLink)
                .isEqualTo("http://" + PORTAL_HOST + "/reset-password#token=" + Fixtures.tokenOf(customerLink));
        assertThat(agentLink)
                .isEqualTo("http://" + CONSOLE_HOST + "/reset-password#token=" + Fixtures.tokenOf(agentLink));
        assertThat(toCustomer.to()).containsExactly(customer.email());
        // This setup has no POPDESK_LOCALE, so accounts are English (OwnProfileTest has the Korean case).
        assertThat(PasswordSteps.hasHangul(toCustomer.subject() + toCustomer.text()))
                .as("Korean in the mail to an English account: %s", toCustomer.text()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(Misleading.class)
    @DisplayName("S1A-AC-82 the link has the configured address whatever Host and X-Forwarded-* the request carries")
    void linkIgnoresRequestHeaders(Misleading headers) {
        Session admin = fixtures.signInAdmin();
        Session customer = fixtures.customer(admin, fixtures.createCompany(admin).code());

        headers.on(portal().post(PasswordSteps.REQUEST)).json(Map.of("email", customer.email()))
                .expect(202);

        String link = PasswordSteps.linkIn(PasswordSteps.awaitMails(mail(), customer.email(), 1).getFirst().text());
        assertThat(link).isEqualTo("http://" + PORTAL_HOST + "/reset-password#token=" + Fixtures.tokenOf(link));
    }

    @Test
    @DisplayName("S1A-AC-12 a second request within 15 minutes sends no mail and the first link still works")
    void secondRequestWithinFifteenMinutesSendsNothing() {
        Session admin = fixtures.signInAdmin();
        String code = fixtures.createCompany(admin).code();
        Session customer = fixtures.customer(admin, code);
        Session later = fixtures.customer(admin, code);
        String token = PasswordSteps.requestToken(portal(), mail(), customer.email());

        clock.advance(Duration.ofMinutes(14));
        PasswordSteps.request(portal(), customer.email()).expectStatus(202);
        PasswordSteps.request(portal(), later.email()).expectStatus(202);

        PasswordSteps.awaitMails(mail(), later.email(), 1);
        Wait.holds("one reset mail to " + customer.email(), Duration.ofSeconds(2),
                () -> PasswordSteps.mails(mail(), customer.email()).size() == 1);
        PasswordSteps.confirm(portal(), token, PasswordSteps.NEW_PASSWORD).expectStatus(204);
    }

    @Test
    @DisplayName("S1A-AC-12 a second request exactly 15 minutes after the first mail sends a mail with another "
            + "link, which works")
    void secondRequestAfterFifteenMinutesSendsANewLink() {
        Session admin = fixtures.signInAdmin();
        Session customer = fixtures.customer(admin, fixtures.createCompany(admin).code());
        String first = PasswordSteps.requestToken(portal(), mail(), customer.email());

        clock.advance(Duration.ofMinutes(15));
        PasswordSteps.request(portal(), customer.email()).expectStatus(202);

        List<ReceivedMail> mails = PasswordSteps.awaitMails(mail(), customer.email(), 2);
        String second = Fixtures.tokenOf(PasswordSteps.linkIn(mails.get(1).text()));
        assertThat(second).isNotEqualTo(first);
        PasswordSteps.confirm(portal(), second, PasswordSteps.NEW_PASSWORD).expectStatus(204);
    }

    @Test
    @DisplayName("S1A-AC-85 a request alone leaves the account's sessions, sign-in lock, and password as they were")
    void requestAloneChangesNothing() {
        Session admin = fixtures.signInAdmin();
        Session customer = fixtures.customer(admin, fixtures.createCompany(admin).code());
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertError(AuthChecks.signIn(portal(), customer.email(), "wrong-Password-" + attempt,
                    AuthChecks.freshIp()), 401, "SIGN_IN_FAILED");
        }

        PasswordSteps.requestLink(portal(), mail(), customer.email());

        assertThat(customer.client().get("/api/portal/me").expect(200).jsonLong("id"))
                .as("the session from before the request").isEqualTo(customer.accountId());
        assertError(AuthChecks.signIn(portal(), customer.email(), customer.password(), AuthChecks.freshIp()),
                401, "SIGN_IN_FAILED");
        clock.advance(Duration.ofMinutes(16));
        AuthChecks.signIn(portal(), customer.email(), customer.password(), AuthChecks.freshIp()).expectStatus(204);
    }

    private Asked arrange(Silent which) {
        Session admin = fixtures.signInAdmin();
        String code = fixtures.createCompany(admin).code();
        Session customer = fixtures.customer(admin, code);
        Session agent = fixtures.agent(admin, code);
        return switch (which) {
            case UNKNOWN_EMAIL -> new Asked(App.PORTAL, Unique.email("nobody"), customer);
            case SUSPENDED_ACCOUNT -> {
                Session suspended = fixtures.customer(admin, code);
                admin.client().post("/api/console/accounts/" + suspended.accountId() + "/suspend").expect(200);
                yield new Asked(App.PORTAL, suspended.email(), customer);
            }
            case INVITATION_NOT_ACCEPTED ->
                    new Asked(App.PORTAL, fixtures.invite(admin, Role.CUSTOMER, code).email(), customer);
            case CUSTOMER_ON_THE_CONSOLE -> new Asked(App.CONSOLE, customer.email(), agent);
            case AGENT_ON_THE_PORTAL -> new Asked(App.PORTAL, agent.email(), customer);
        };
    }
}
