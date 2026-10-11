package io.popdesk.auth;

import static io.popdesk.auth.AuthChecks.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.App;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Changes to an account that end its sessions (AUTH-4): suspension, a role change, and the account's
 * own password change, which keeps only the session that made it. A session has ended when the same
 * cookie gets 401 on the next request.
 */
class SessionEndingTest extends BareAppTest {

    /** A role change an admin may make. */
    enum RoleChange {
        AGENT_TO_ADMIN(Role.AGENT, Role.ADMIN),
        ADMIN_TO_AGENT(Role.ADMIN, Role.AGENT);

        private final Role from;
        private final Role to;

        RoleChange(Role from, Role to) {
            this.from = from;
            this.to = to;
        }
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"CUSTOMER", "AGENT"})
    @DisplayName("S1A-AC-01 suspending an account ends all its sessions: they get 401, also after the account "
            + "is active again")
    void suspensionEndsEverySession(Role role) {
        Session admin = fixtures.signInAdmin();
        Session first = person(admin, role);
        Session second = fixtures.signInAgain(first);
        second.client().get(second.api("/me")).expect(200);

        admin.client().post("/api/console/accounts/" + first.accountId() + "/suspend").expect(200);

        assertError(first.client().get(first.api("/me")).send(), 401, "UNAUTHENTICATED");
        assertError(second.client().get(second.api("/me")).send(), 401, "UNAUTHENTICATED");
        admin.client().post("/api/console/accounts/" + first.accountId() + "/reactivate").expect(200);
        assertError(first.client().get(first.api("/me")).send(), 401, "UNAUTHENTICATED");
        assertError(second.client().get(second.api("/me")).send(), 401, "UNAUTHENTICATED");
    }

    @ParameterizedTest
    @EnumSource(App.class)
    @DisplayName("S1A-AC-02 changing one's own password ends the account's other sessions and carries this one on "
            + "under a new session ID")
    void ownPasswordChangeKeepsOnlyThisSession(App app) {
        Session admin = fixtures.signInAdmin();
        Session mine = person(admin, app == App.PORTAL ? Role.CUSTOMER : Role.AGENT);
        Session other = fixtures.signInAgain(mine);
        Map.Entry<String, String> before = sessionCookie(mine.client());

        mine.client().put(mine.api("/me/password"))
                .json(Map.of("currentPassword", mine.password(), "newPassword", PasswordSteps.NEW_PASSWORD))
                .expect(204);

        Map.Entry<String, String> after = sessionCookie(mine.client());
        assertThat(after.getKey()).as("name of the session cookie").isEqualTo(before.getKey());
        assertThat(after.getValue()).as("session ID after the change").isNotEqualTo(before.getValue());
        assertThat(mine.client().get(mine.api("/me")).expect(200).jsonLong("id")).isEqualTo(mine.accountId());
        ApiClient withOldId = client(app);
        withOldId.setCookie(before.getKey(), before.getValue());
        assertError(withOldId.get(mine.api("/me")).send(), 401, "UNAUTHENTICATED");
        assertError(other.client().get(other.api("/me")).send(), 401, "UNAUTHENTICATED");
    }

    @ParameterizedTest
    @EnumSource(RoleChange.class)
    @DisplayName("S1A-AC-03 changing an account's role ends all its sessions; the next sign-in has the new role")
    void roleChangeEndsEverySession(RoleChange change) {
        Session admin = fixtures.signInAdmin();
        Session first = person(admin, change.from);
        Session second = fixtures.signInAgain(first);
        second.client().get("/api/console/me").expect(200);

        admin.client().put("/api/console/accounts/" + first.accountId() + "/role")
                .json(Map.of("role", change.to.name()))
                .expect(200);

        assertError(first.client().get("/api/console/me").send(), 401, "UNAUTHENTICATED");
        assertError(second.client().get("/api/console/me").send(), 401, "UNAUTHENTICATED");
        assertThat(fixtures.signInAgain(first).role()).isEqualTo(change.to);
    }

    private Session person(Session admin, Role role) {
        return switch (role) {
            case CUSTOMER -> fixtures.customer(admin, fixtures.createCompany(admin).code());
            case AGENT -> fixtures.agent(admin);
            case ADMIN -> fixtures.admin(admin);
        };
    }

    /** The one cookie of a signed-in client that is not the CSRF token: the session. */
    private static Map.Entry<String, String> sessionCookie(ApiClient client) {
        var cookies = client.cookies().entrySet().stream()
                .filter(c -> !c.getKey().equals(ApiClient.CSRF_COOKIE))
                .toList();
        assertThat(cookies).as("cookies of %s other than the CSRF token", client).hasSize(1);
        return cookies.getFirst();
    }
}
