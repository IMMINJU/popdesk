package io.popdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.auth.OidcFlow.Started;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Concurrently;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.OidcProvider.Redirect;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Two first OIDC sign-ins of one account at the same time (S11-AC-20): both browsers start and log in at the
 * provider first, then both callbacks go at once. The end is as one after the other: one link, no 500.
 */
class OidcConcurrentLinkTest extends OidcAppTest {

    private static final String CONSOLE = "/api/console";

    @Test
    @DisplayName("S11-AC-20 two first sign-ins of one account with the same sub at the same time answer 302 without "
            + "a 500, at least one signs in, and the sub signs in after")
    void sameSubjectTwiceAtOnce() {
        Session admin = fixtures.signInAdmin();
        Session agent = person(admin, Role.AGENT);
        String sub = OidcFlow.newSub();

        List<Outcome> outcomes = atOnce(agent, sub, sub);

        assertThat(outcomes).as("outcomes").allSatisfy(o -> assertThat(o.status()).as(o.toString()).isEqualTo(302));
        assertThat(outcomes.stream().filter(Outcome::signedIn).count()).as("sign-ins that started a session")
                .isGreaterThanOrEqualTo(1);
        assertThat(oidc.signedIn(console(), CONSOLE, sub, oidc.times())).as("the sub alone after")
                .isEqualTo(agent.accountId());
    }

    @Test
    @DisplayName("S11-AC-20 two first sign-ins of one account with two subs at the same time answer 302 without a "
            + "500, exactly one signs in and the other goes to /login?oidc=failed, and only the winner's sub signs "
            + "in after")
    void twoSubjectsAtOnceLinkOne() {
        Session admin = fixtures.signInAdmin();
        Session agent = person(admin, Role.AGENT);
        String first = OidcFlow.newSub();
        String second = OidcFlow.newSub();

        List<Outcome> outcomes = atOnce(agent, first, second);

        assertThat(outcomes).as("outcomes").allSatisfy(o -> assertThat(o.status()).as(o.toString()).isEqualTo(302));
        List<Outcome> won = outcomes.stream().filter(Outcome::signedIn).toList();
        List<Outcome> lost = outcomes.stream().filter(o -> !o.signedIn()).toList();
        assertThat(won).as("sign-ins that started a session in %s", outcomes).hasSize(1);
        assertThat(lost.getFirst().location()).as("Location of the other").isEqualTo("/login?oidc=failed");
        assertThat(oidc.signedIn(console(), CONSOLE, won.getFirst().sub(), oidc.times()))
                .as("the winner's sub alone after").isEqualTo(agent.accountId());
        ApiClient browser = console();
        OidcFlow.assertBackAtLogin(browser, oidc.signIn(browser, lost.getFirst().sub(), oidc.claims(agent.email())),
                "failed");
    }

    /** Starts and logs in two browsers as {@code subA} and {@code subB}, then sends both callbacks at once. */
    private List<Outcome> atOnce(Session agent, String subA, String subB) {
        List<ApiClient> browsers = List.of(console(), console());
        List<String> subs = List.of(subA, subB);
        List<Redirect> back = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Started started = oidc.started(browsers.get(i));
            back.add(oidc.logIn(started, subs.get(i), oidc.claims(agent.email())));
        }
        List<Supplier<ApiResponse>> calls = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            int n = i;
            calls.add(() -> oidc.callback(browsers.get(n), back.get(n)));
        }

        List<ApiResponse> answers = Concurrently.send(2, calls);

        List<Outcome> out = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            ApiClient browser = browsers.get(i);
            ApiResponse answer = answers.get(i);
            boolean signedIn = browser.get(CONSOLE + "/me").send().status() == 200;
            out.add(new Outcome(subs.get(i), answer.status(), OidcFlow.appLocation(browser, answer), signedIn));
        }
        return out;
    }

    private record Outcome(String sub, int status, String location, boolean signedIn) {}
}
