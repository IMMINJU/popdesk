package io.popdesk.auth;

import static io.popdesk.auth.AuthChecks.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.AppTest;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.App;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A session lasts 8 hours from sign-in, reads assignments from the database, and ends at sign-out. */
class SessionLifetimeTest extends BareAppTest {

    @Test
    @DisplayName("S0-AC-25 a session ends 8 hours after sign-in, even when it was used in between")
    void sessionEndsEightHoursAfterSignIn() {
        Session admin = AuthChecks.signedIn(console(), App.CONSOLE, AppTest.ADMIN_EMAIL, AppTest.ADMIN_PASSWORD);

        clock.advance(Duration.ofHours(7).plusMinutes(59));
        admin.client().get("/api/console/me").expect(200);
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));

        assertError(admin.client().get("/api/console/me").send(), 401, "UNAUTHENTICATED");
        assertError(admin.client().get("/api/console/companies").send(), 401, "UNAUTHENTICATED");
    }

    @Test
    @DisplayName("S0-AC-26 an agent assigned to a company while signed in sees its issues on the next request")
    void assignmentTakesEffectWithoutSigningInAgain() {
        Session admin = AuthChecks.signedIn(console(), App.CONSOLE, AppTest.ADMIN_EMAIL, AppTest.ADMIN_PASSWORD);
        Company first = fixtures.createCompany(admin);
        Company second = fixtures.createCompany(admin);
        Session agent = fixtures.agent(admin, first.code());
        Session customer = fixtures.customer(admin, second.code());
        Issue issue = fixtures.fileIssue(customer, second.code());
        ApiClient agentBrowser = agent.client();

        assertError(agentBrowser.get("/api/console/issues/" + issue.key()).send(), 404, "NOT_FOUND");
        assertThat(agentBrowser.get("/api/console/issues").query("size", 100).expect(200).json().path("items")
                .findValuesAsString("key")).doesNotContain(issue.key());

        fixtures.assignAgent(admin, second.code(), agent.accountId());

        assertThat(agentBrowser.get("/api/console/issues/" + issue.key()).expect(200).jsonText("key"))
                .isEqualTo(issue.key());
        assertThat(agentBrowser.get("/api/console/issues").query("size", 100).expect(200).json().path("items")
                .findValuesAsString("key")).contains(issue.key());
        assertThat(agentBrowser.get("/api/console/companies").query("size", 100).expect(200).json().path("items")
                .findValuesAsString("code")).contains(first.code(), second.code());
    }

    @Test
    @DisplayName("S0-AC-27 sign-out deletes the session: its cookie no longer works, other sessions still do")
    void signOutDeletesTheSession() {
        Session admin = AuthChecks.signedIn(console(), App.CONSOLE, AppTest.ADMIN_EMAIL, AppTest.ADMIN_PASSWORD);
        Session otherBrowser = AuthChecks.signedIn(console(), App.CONSOLE, AppTest.ADMIN_EMAIL,
                AppTest.ADMIN_PASSWORD);
        Map<String, String> cookiesBefore = admin.client().cookies();
        ApiClient copy = console();
        cookiesBefore.forEach(copy::setCookie);
        copy.get("/api/console/me").expect(200);

        admin.client().post("/api/public/sign-out").expect(204);

        assertError(admin.client().get("/api/console/me").send(), 401, "UNAUTHENTICATED");
        assertError(copy.get("/api/console/me").send(), 401, "UNAUTHENTICATED");
        otherBrowser.client().get("/api/console/me").expect(200);
    }
}
