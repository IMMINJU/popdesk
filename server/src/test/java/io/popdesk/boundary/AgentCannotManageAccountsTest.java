package io.popdesk.boundary;

import static io.popdesk.boundary.BoundaryChecks.allPages;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * The account and invitation operations slice 1a adds are for admins only. This is the boundary the
 * boundary list names for them: an agent, also one assigned to the target's company, gets 403 and
 * the account stays as it was.
 */
class AgentCannotManageAccountsTest extends BareAppTest {

    private static JsonNode find(Session admin, String path, String idField, long accountId) {
        return allPages(admin, path).items().stream()
                .filter(n -> n.path(idField).asLong(-1) == accountId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No account " + accountId + " in " + path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"rename", "role", "suspend", "reactivate", "password-reset"})
    @DisplayName("S1A-AC-77 an agent renaming an account, changing its role, suspending or reactivating it, or "
            + "resetting its password gets 403 FORBIDDEN, and the account keeps its name, role, status, and password")
    void agentCannotChangeAnAccount(String action) {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session agent = fixtures.agent(admin, company.code());
        Session target = action.equals("role") ? fixtures.agent(admin, company.code())
                : fixtures.customer(admin, company.code());
        String account = "/api/console/accounts/" + target.accountId();
        ApiRequest request = switch (action) {
            case "rename" -> agent.client().patch(account).json(Map.of("name", "Renamed by an agent"));
            case "role" -> agent.client().put(account + "/role").json(Map.of("role", "ADMIN"));
            default -> agent.client().post(account + "/" + action);
        };

        ApiResponse response = request.send();

        assertThat(response.status()).as("%s: %s", response, response.snippet()).isEqualTo(403);
        assertThat(response.errorCode()).isEqualTo("FORBIDDEN");
        JsonNode after = find(admin, "/api/console/accounts", "id", target.accountId());
        assertThat(after.path("name").asString(null)).as("name in %s", after).isEqualTo(target.name());
        assertThat(after.path("role").asString(null)).as("role in %s", after).isEqualTo(target.role().name());
        assertThat(after.path("status").asString(null)).as("status in %s", after).isEqualTo("ACTIVE");
        assertThat(fixtures.signInAgain(target).accountId()).as("the target still signs in with its password")
                .isEqualTo(target.accountId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"resend", "cancel"})
    @DisplayName("S1A-AC-77 an agent sending an invitation again or cancelling it gets 403 FORBIDDEN, and the "
            + "invitation stays pending with its link working")
    void agentCannotChangeAnInvitation(String action) {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session agent = fixtures.agent(admin, company.code());
        Invitation invitation = fixtures.invite(admin, Role.CUSTOMER, company.code());

        ApiResponse response = agent.client().post("/api/console/invitations/" + invitation.accountId() + "/" + action)
                .send();

        assertThat(response.status()).as("%s: %s", response, response.snippet()).isEqualTo(403);
        assertThat(response.errorCode()).isEqualTo("FORBIDDEN");
        JsonNode pending = find(admin, "/api/console/invitations", "accountId", invitation.accountId());
        assertThat(pending.path("cancelled").asBoolean(true)).as("cancelled in %s", pending).isFalse();
        assertThat(fixtures.accept(invitation).accountId()).as("the first link still works")
                .isEqualTo(invitation.accountId());
    }
}
