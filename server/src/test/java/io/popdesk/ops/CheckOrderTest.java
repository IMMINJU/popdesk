package io.popdesk.ops;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The order of checks (conventions "Order of checks", COM-1, COM-2): 401, then a role check, then
 * 404 for a target out of scope, then 403 for a target the caller sees but may not act on, then
 * 400, then 400 or 409 for the current state. Each request below is wrong in several ways at once;
 * the answer must be the first check in that order. Every endpoint of both contracts but the
 * public ones (sign-in, sign-out, CSRF, accepting an invitation) is in the 401 list.
 */
class CheckOrderTest extends BareAppTest {

    private static final String NO_KEY = "NOPE-999999";
    private static final long NO_ID = 999_999_999L;
    private static final String NO_CODE = "ZZ9NOPE";

    /** One request and the answer it must get. */
    private record Case(String what, Supplier<ApiRequest> request, int status, String code) {}

    private static void check(List<Case> cases) {
        SoftAssertions soft = new SoftAssertions();
        for (Case c : cases) {
            ApiResponse response = c.request().get().send();
            soft.assertThat(response.status()).as("%s: %s -> %s", c.what(), response.request(), response.snippet())
                    .isEqualTo(c.status());
            if (response.status() == c.status()) {
                soft.assertThat(response.errorCode()).as("code of %s", c.what()).isEqualTo(c.code());
            }
        }
        soft.assertAll();
    }

    private static Map<String, Object> badCompany(String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", "");
        body.put("legalName", "x".repeat(101));
        return body;
    }

    private static Map<String, Object> badInvitation(String email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("name", "");
        body.put("role", "CUSTOMER");
        return body;
    }

    private static ApiRequest emptyUpload(ApiClient client, String path) {
        return client.post(path).file("file", "empty.txt", "text/plain", new byte[0]);
    }

    @Test
    @DisplayName("S0-AC-85 without a session every endpoint answers 401 first, whatever else is wrong")
    void unauthenticatedFirst() {
        ApiClient c = console();
        ApiClient p = portal();
        String ci = "/api/console/issues/" + NO_KEY;
        String pi = "/api/portal/issues/" + NO_KEY;
        List<Case> cases = new ArrayList<>(List.of(
                new Case("list accounts", () -> c.get("/api/console/accounts").query("size", 0), 401, "UNAUTHENTICATED"),
                new Case("list companies", () -> c.get("/api/console/companies").query("size", 0), 401, "UNAUTHENTICATED"),
                new Case("create company", () -> c.post("/api/console/companies").json(badCompany("x")), 401,
                        "UNAUTHENTICATED"),
                new Case("get company", () -> c.get("/api/console/companies/" + NO_CODE), 401, "UNAUTHENTICATED"),
                new Case("company agents", () -> c.get("/api/console/companies/" + NO_CODE + "/agents").query("size", 0),
                        401, "UNAUTHENTICATED"),
                new Case("assign agent", () -> c.put("/api/console/companies/" + NO_CODE + "/agents/" + NO_ID), 401,
                        "UNAUTHENTICATED"),
                new Case("company customers",
                        () -> c.get("/api/console/companies/" + NO_CODE + "/customers").query("size", 0), 401,
                        "UNAUTHENTICATED"),
                new Case("pending invitations", () -> c.get("/api/console/invitations").query("size", 0), 401,
                        "UNAUTHENTICATED"),
                new Case("invite", () -> c.post("/api/console/invitations").json(badInvitation("x")), 401,
                        "UNAUTHENTICATED"),
                new Case("invitation link", () -> c.post("/api/console/invitations/" + NO_ID + "/link"), 401,
                        "UNAUTHENTICATED"),
                new Case("console issues", () -> c.get("/api/console/issues").query("size", 0), 401, "UNAUTHENTICATED"),
                new Case("console issue", () -> c.get(ci), 401, "UNAUTHENTICATED"),
                new Case("console attachments", () -> c.get(ci + "/attachments").query("size", 0), 401,
                        "UNAUTHENTICATED"),
                new Case("console upload", () -> emptyUpload(c, ci + "/attachments"), 401, "UNAUTHENTICATED"),
                new Case("console delete attachment", () -> c.delete(ci + "/attachments/nope"), 401, "UNAUTHENTICATED"),
                new Case("console download", () -> c.get(ci + "/attachments/nope"), 401, "UNAUTHENTICATED"),
                new Case("console comments", () -> c.get(ci + "/comments").query("size", 0), 401, "UNAUTHENTICATED"),
                new Case("console comment", () -> c.post(ci + "/comments").json(Map.of("content", "")), 401,
                        "UNAUTHENTICATED"),
                new Case("console me", () -> c.get("/api/console/me"), 401, "UNAUTHENTICATED"),
                new Case("notifications", () -> c.get("/api/console/notifications").query("size", 0), 401,
                        "UNAUTHENTICATED"),
                new Case("unread count", () -> c.get("/api/console/notifications/unread-count"), 401,
                        "UNAUTHENTICATED"),
                new Case("mark read", () -> c.post("/api/console/notifications/" + NO_ID + "/read"), 401,
                        "UNAUTHENTICATED"),
                new Case("system status", () -> c.get("/api/console/system/status"), 401, "UNAUTHENTICATED"),
                new Case("failed deliveries", () -> c.get("/api/console/system/failed-deliveries").query("size", 0),
                        401, "UNAUTHENTICATED"),
                new Case("portal companies", () -> p.get("/api/portal/companies").query("size", 0), 401,
                        "UNAUTHENTICATED"),
                new Case("portal issues", () -> p.get("/api/portal/issues").query("size", 0), 401, "UNAUTHENTICATED"),
                new Case("file issue", () -> p.post("/api/portal/issues").json(Map.of("companyCode", NO_CODE,
                        "title", "")), 401, "UNAUTHENTICATED"),
                new Case("portal issue", () -> p.get(pi), 401, "UNAUTHENTICATED"),
                new Case("portal attachments", () -> p.get(pi + "/attachments").query("size", 0), 401,
                        "UNAUTHENTICATED"),
                new Case("portal upload", () -> emptyUpload(p, pi + "/attachments"), 401, "UNAUTHENTICATED"),
                new Case("portal delete attachment", () -> p.delete(pi + "/attachments/nope"), 401, "UNAUTHENTICATED"),
                new Case("portal download", () -> p.get(pi + "/attachments/nope"), 401, "UNAUTHENTICATED"),
                new Case("portal comments", () -> p.get(pi + "/comments").query("size", 0), 401, "UNAUTHENTICATED"),
                new Case("portal comment", () -> p.post(pi + "/comments").json(Map.of("content", "")), 401,
                        "UNAUTHENTICATED"),
                new Case("portal me", () -> p.get("/api/portal/me"), 401, "UNAUTHENTICATED")));
        check(cases);
    }

    @Test
    @DisplayName("S0-AC-85 an agent on an admin-only endpoint gets 403 before a missing target or bad input")
    void roleBeforeTargetAndInput() {
        Session admin = fixtures.signInAdmin();
        ApiClient agent = fixtures.agent(admin).client();

        check(List.of(
                new Case("list accounts, size 0", () -> agent.get("/api/console/accounts").query("size", 0), 403,
                        "FORBIDDEN"),
                new Case("create company, bad body", () -> agent.post("/api/console/companies")
                        .json(badCompany("x")), 403, "FORBIDDEN"),
                new Case("agents of a missing company, size 0", () -> agent.get("/api/console/companies/" + NO_CODE
                        + "/agents").query("size", 0), 403, "FORBIDDEN"),
                new Case("assign to a missing company", () -> agent.put("/api/console/companies/" + NO_CODE
                        + "/agents/" + NO_ID), 403, "FORBIDDEN"),
                new Case("customers of a missing company, size 0", () -> agent.get("/api/console/companies/"
                        + NO_CODE + "/customers").query("size", 0), 403, "FORBIDDEN"),
                new Case("pending invitations, size 0", () -> agent.get("/api/console/invitations")
                        .query("size", 0), 403, "FORBIDDEN"),
                new Case("invite, bad body", () -> agent.post("/api/console/invitations")
                        .json(badInvitation("x")), 403, "FORBIDDEN"),
                new Case("link of a missing account", () -> agent.post("/api/console/invitations/" + NO_ID + "/link"),
                        403, "FORBIDDEN"),
                new Case("system status", () -> agent.get("/api/console/system/status"), 403, "FORBIDDEN"),
                new Case("failed deliveries, size 0", () -> agent.get("/api/console/system/failed-deliveries")
                        .query("size", 0), 403, "FORBIDDEN")));
    }

    @Test
    @DisplayName("S0-AC-85 a target out of scope is 404 before a 403 or bad input")
    void scopeBeforePermissionAndInput() {
        OpsWorld w = OpsWorld.build(fixtures);
        ApiClient admin = w.admin().client();
        ApiClient outsider = w.agentNowhere().client();
        ApiClient customerB = w.customerB().client();
        String agentPath = w.issuePath(w.agentNowhere());
        String portalPath = w.issuePath(w.customerB());

        check(List.of(
                new Case("admin: agents of a missing company, size 0", () -> admin.get("/api/console/companies/"
                        + NO_CODE + "/agents").query("size", 0), 404, "NOT_FOUND"),
                new Case("admin: assign a customer to a missing company", () -> admin.put("/api/console/companies/"
                        + NO_CODE + "/agents/" + w.customerA().accountId()), 404, "NOT_FOUND"),
                new Case("admin: customers of a missing company, size 0", () -> admin.get("/api/console/companies/"
                        + NO_CODE + "/customers").query("size", 0), 404, "NOT_FOUND"),
                new Case("admin: mark a missing item read", () -> admin.post("/api/console/notifications/" + NO_ID
                        + "/read"), 404, "NOT_FOUND"),
                new Case("agent elsewhere: company A", () -> outsider.get("/api/console/companies/" + w.a().code()),
                        404, "NOT_FOUND"),
                new Case("agent elsewhere: issue of A", () -> outsider.get(agentPath), 404, "NOT_FOUND"),
                new Case("agent elsewhere: comments, size 0", () -> outsider.get(agentPath + "/comments")
                        .query("size", 0), 404, "NOT_FOUND"),
                new Case("agent elsewhere: empty comment", () -> outsider.post(agentPath + "/comments")
                        .json(Map.of("content", "")), 404, "NOT_FOUND"),
                new Case("agent elsewhere: attachments, size 0", () -> outsider.get(agentPath + "/attachments")
                        .query("size", 0), 404, "NOT_FOUND"),
                new Case("agent elsewhere: empty upload", () -> emptyUpload(outsider, agentPath + "/attachments"),
                        404, "NOT_FOUND"),
                new Case("agent elsewhere: delete attachment", () -> outsider.delete(w.attachmentPath(w.agentNowhere())),
                        404, "NOT_FOUND"),
                new Case("customer of B: file in A with an empty title", () -> customerB.post("/api/portal/issues")
                        .json(Map.of("companyCode", w.a().code(), "title", "", "content", "x")), 404, "NOT_FOUND"),
                new Case("customer of B: issue of A", () -> customerB.get(portalPath), 404, "NOT_FOUND"),
                new Case("customer of B: comments, size 0", () -> customerB.get(portalPath + "/comments")
                        .query("size", 0), 404, "NOT_FOUND"),
                new Case("customer of B: empty comment", () -> customerB.post(portalPath + "/comments")
                        .json(Map.of("content", "")), 404, "NOT_FOUND"),
                new Case("customer of B: attachments, size 0", () -> customerB.get(portalPath + "/attachments")
                        .query("size", 0), 404, "NOT_FOUND"),
                new Case("customer of B: empty upload", () -> emptyUpload(customerB, portalPath + "/attachments"),
                        404, "NOT_FOUND"),
                new Case("customer of B: delete attachment", () -> customerB.delete(w.attachmentPath(w.customerB())),
                        404, "NOT_FOUND")));
    }

    @Test
    @DisplayName("S0-AC-85 a target the caller sees but may not act on is 403 before bad input")
    void permissionBeforeInput() {
        OpsWorld w = OpsWorld.build(fixtures);
        ApiClient colleague = w.colleagueA().client();
        ApiClient agent = w.agentA().client();
        String colleaguePath = w.issuePath(w.colleagueA());

        check(List.of(
                new Case("colleague: empty comment on someone else's public issue", () -> colleague
                        .post(colleaguePath + "/comments").json(Map.of("content", "")), 403, "FORBIDDEN"),
                new Case("colleague: empty upload to someone else's public issue", () -> emptyUpload(colleague,
                        colleaguePath + "/attachments"), 403, "FORBIDDEN"),
                new Case("colleague: delete someone else's attachment", () -> colleague
                        .delete(w.attachmentPath(w.colleagueA())), 403, "FORBIDDEN"),
                new Case("assigned agent: delete the customer's attachment", () -> agent
                        .delete(w.attachmentPath(w.agentA())), 403, "FORBIDDEN")));
    }

    @Test
    @DisplayName("S0-AC-85 bad input is 400 before a conflict with the current state (409)")
    void inputBeforeConflict() {
        Session admin = fixtures.signInAdmin();
        String taken = fixtures.createCompany(admin).code();
        String email = fixtures.invite(admin, Role.AGENT).email();
        ApiClient client = admin.client();

        check(List.of(
                new Case("company with a taken code and an empty name", () -> client.post("/api/console/companies")
                        .json(badCompany(taken)), 400, "INVALID_INPUT"),
                new Case("invitation with a taken email and an empty name", () -> client
                        .post("/api/console/invitations").json(badInvitation(email)), 400, "INVALID_INPUT"),
                new Case("invitation with a taken email in other case and an empty name", () -> client
                        .post("/api/console/invitations").json(badInvitation(email.toUpperCase(Locale.ROOT))),
                        400, "INVALID_INPUT")));
        // The same requests with valid input reach the state check.
        check(List.of(
                new Case("company with a taken code", () -> client.post("/api/console/companies")
                        .json(Map.of("code", taken, "name", "N", "legalName", "L")), 409, "COMPANY_CODE_TAKEN"),
                new Case("invitation with a taken email", () -> client.post("/api/console/invitations")
                        .json(Map.of("email", email, "name", Unique.text("Again"), "role", "AGENT")), 409,
                        "EMAIL_TAKEN")));
    }
}
