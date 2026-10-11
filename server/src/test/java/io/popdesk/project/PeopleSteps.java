package io.popdesk.project;

import io.popdesk.auth.PasswordSteps;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Steps of the people and company tests of slice 1a, through the admin API: the paths, and the calls
 * a test makes only to reach a state (a suspended account, a cancelled invitation, an archived
 * company). Each step expects its one status, so before the slice is built a test fails there, on an
 * assertion. Public for the one step another package shares (the admin's password reset).
 */
public final class PeopleSteps {

    private PeopleSteps() {}

    // Paths

    static String account(long accountId) {
        return "/api/console/accounts/" + accountId;
    }

    static String invitation(long accountId, String action) {
        return "/api/console/invitations/" + accountId + "/" + action;
    }

    static String company(String code) {
        return "/api/console/companies/" + code;
    }

    static String customerOf(String code, long accountId) {
        return company(code) + "/customers/" + accountId;
    }

    static String agentOf(String code, long accountId) {
        return company(code) + "/agents/" + accountId;
    }

    // Accounts

    static ApiResponse suspend(Session admin, long accountId) {
        return admin.client().post(account(accountId) + "/suspend").expect(200);
    }

    static ApiResponse reactivate(Session admin, long accountId) {
        return admin.client().post(account(accountId) + "/reactivate").expect(200);
    }

    static ApiRequest roleChange(Session admin, long accountId, Role role) {
        return admin.client().put(account(accountId) + "/role").json(Map.of("role", role.name()));
    }

    /** Resets the password of an account; the response holds {@code resetLink}. */
    public static ApiResponse resetPassword(Session admin, long accountId) {
        return admin.client().post(account(accountId) + "/password-reset").expect(200);
    }

    /** Sets a new password with the token of a reset link, on the browser's host, whatever the answer. */
    static ApiResponse confirmReset(ApiClient browser, String resetLink, String newPassword) {
        return PasswordSteps.confirm(browser, Fixtures.tokenOf(resetLink), newPassword);
    }

    static ApiResponse signInAttempt(ApiClient browser, String email, String password) {
        return PasswordSteps.signIn(browser, email, password);
    }

    /** Every account of the list narrowed by {@code query} (name, value, name, value, ...). */
    static List<JsonNode> accounts(Session admin, Object... query) {
        List<JsonNode> out = new ArrayList<>();
        for (int page = 1; page <= 1000; page++) {
            ApiRequest request = admin.client().get("/api/console/accounts").query("page", page).query("size", 100);
            for (int i = 0; i < query.length; i += 2) {
                request.query((String) query[i], query[i + 1]);
            }
            ApiResponse response = request.expect(200);
            JsonNode items = response.json().path("items");
            if (!items.isArray()) {
                throw new AssertionError(response.request() + ": no items array in " + response.snippet());
            }
            items.forEach(out::add);
            if (page >= response.json().path("totalPages").asInt(0)) {
                return out;
            }
        }
        throw new AssertionError("The account list has more than 1000 pages");
    }

    static List<Long> ids(List<JsonNode> accounts) {
        return accounts.stream().map(n -> n.path("id").asLong(-1)).toList();
    }

    // Invitations

    static ApiResponse resend(Session admin, long accountId) {
        return admin.client().post(invitation(accountId, "resend")).expect(200);
    }

    static void cancel(Session admin, long accountId) {
        admin.client().post(invitation(accountId, "cancel")).expect(204);
    }

    // Companies

    static ApiResponse setStatus(Session admin, String code, String status) {
        return admin.client().put(company(code) + "/status").json(Map.of("status", status)).expect(200);
    }

    static String statusOf(Session admin, String code) {
        return admin.client().get(company(code)).expect(200).jsonText("status");
    }

    static ApiResponse addCustomer(Session admin, String code, long accountId) {
        return admin.client().put(customerOf(code, accountId)).expect(200);
    }

    static void removeCustomer(Session admin, String code, long accountId) {
        admin.client().delete(customerOf(code, accountId)).expect(200);
    }

    static void unassignAgent(Session admin, String code, long accountId) {
        admin.client().delete(agentOf(code, accountId)).expect(200);
    }

    /** The account IDs in a company's customer or agent list ({@code customers}, {@code agents}). */
    static List<Long> members(Session admin, String code, String kind) {
        return ProjectChecks.allItems(admin.client(), company(code) + "/" + kind).stream()
                .map(n -> n.path("accountId").asLong(-1)).toList();
    }
}
