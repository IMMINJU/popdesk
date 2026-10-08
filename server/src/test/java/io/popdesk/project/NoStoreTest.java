package io.popdesk.project;

import static io.popdesk.project.ProjectChecks.assertNoStore;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code Cache-Control: no-store} on the three endpoints that carry or take an invitation token,
 * and on every {@code /api} response: every operation in both contract files, on the host that
 * serves it.
 */
class NoStoreTest extends BareAppTest {

    private static final Set<String> METHODS = Set.of("get", "head", "post", "put", "patch", "delete");

    @Test
    @DisplayName("S0-AC-101 the invitation response has Cache-Control no-store")
    void invitationResponse() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);

        ApiResponse response = InvitationTest.invite(admin, Unique.email("nostore"), "No store", "CUSTOMER",
                List.of(company.code()));

        response.expectStatus(201);
        assertNoStore(response);
    }

    @Test
    @DisplayName("S0-AC-101 the copy-link response has Cache-Control no-store")
    void copyLinkResponse() {
        Session admin = fixtures.signInAdmin();
        Invitation invitation = fixtures.invite(admin, Role.AGENT);

        ApiResponse response = admin.client().post("/api/console/invitations/" + invitation.accountId() + "/link")
                .send();

        response.expectStatus(200);
        assertNoStore(response);
    }

    @Test
    @DisplayName("S0-AC-101 the accept response has Cache-Control no-store, accepted or refused")
    void acceptResponse() {
        Session admin = fixtures.signInAdmin();
        Invitation invitation = fixtures.invite(admin, Role.AGENT);

        ApiResponse accepted = console().post("/api/public/invitations/accept")
                .json(Map.of("token", invitation.token(), "password", Fixtures.PASSWORD))
                .send();
        ApiResponse refused = console().post("/api/public/invitations/accept")
                .json(Map.of("token", invitation.token(), "password", Fixtures.PASSWORD))
                .send();

        accepted.expectStatus(204);
        assertNoStore(accepted);
        refused.expectStatus(400);
        assertNoStore(refused);
    }

    @Test
    @DisplayName("S0-AC-101 every operation of both contracts answers with Cache-Control no-store before sign-in")
    void everyOperationSignedOut() {
        List<String> missing = new ArrayList<>();
        for (Operation op : operations()) {
            for (String host : op.hosts()) {
                check(missing, send(client(host), op), op, host);
            }
        }
        check(missing, portal().get("/api/portal/no-such-path").send(), null, PORTAL_HOST);
        check(missing, console().get("/api/console/no-such-path").send(), null, CONSOLE_HOST);

        assertThat(missing).as("responses without no-store").isEmpty();
    }

    @Test
    @DisplayName("S0-AC-101 every GET and HEAD of both contracts answers with Cache-Control no-store when signed in")
    void everyReadSignedIn() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        List<String> missing = new ArrayList<>();

        for (Operation op : operations()) {
            if (!op.method().equals("GET") && !op.method().equals("HEAD")) {
                continue;
            }
            if (op.path().startsWith("/api/portal/") || op.path().startsWith("/api/public/")) {
                check(missing, send(customer.client(), op), op, PORTAL_HOST);
            }
            if (op.path().startsWith("/api/console/") || op.path().startsWith("/api/public/")) {
                check(missing, send(admin.client(), op), op, CONSOLE_HOST);
            }
        }

        assertThat(missing).as("responses without no-store").isEmpty();
    }

    private static void check(List<String> missing, ApiResponse response, Operation op, String host) {
        boolean noStore = response.headers("Cache-Control").stream()
                .anyMatch(v -> v.toLowerCase(Locale.ROOT).contains("no-store"));
        if (!noStore) {
            missing.add((op == null ? response.request() : op.method() + " " + op.path() + " on " + host) + " -> "
                    + response.status() + " Cache-Control " + response.headers("Cache-Control"));
        }
    }

    private static ApiResponse send(ApiClient client, Operation op) {
        String path = op.path()
                .replace("{key}", "NOSUCH9-1")
                .replace("{code}", "NOSUCH9")
                .replace("{accountId}", "999999999")
                .replace("{id}", "999999999");
        ApiRequest request = client.request(op.method(), path);
        if (Set.of("POST", "PUT", "PATCH").contains(op.method())) {
            request.jsonText("{}");
        }
        return request.send();
    }

    /** An operation of a contract file, and the hosts that serve it. */
    private record Operation(String method, String path, List<String> hosts) {}

    private static List<Operation> operations() {
        List<Operation> out = new ArrayList<>();
        JsonNode portal = contract("portal");
        JsonNode console = contract("console");
        addOperations(out, portal, PORTAL_HOST);
        addOperations(out, console, CONSOLE_HOST);
        assertThat(out).as("operations in the contract files").isNotEmpty();
        return out;
    }

    /** Portal and console paths on their own host; public paths on both, once. */
    private static void addOperations(List<Operation> out, JsonNode contract, String host) {
        contract.path("paths").properties().forEach(path -> path.getValue().properties().forEach(op -> {
            if (!METHODS.contains(op.getKey())) {
                return;
            }
            String method = op.getKey().toUpperCase(Locale.ROOT);
            String p = path.getKey();
            if (p.startsWith("/api/public/")) {
                if (host.equals(PORTAL_HOST)) {
                    out.add(new Operation(method, p, List.of(PORTAL_HOST, CONSOLE_HOST)));
                }
            } else {
                out.add(new Operation(method, p, List.of(host)));
            }
        }));
    }

    private static JsonNode contract(String app) {
        Path file = Path.of("..", "docs", "integrate", "openapi-" + app + ".json");
        try {
            return JsonMapper.builder().build().readTree(Files.readString(file));
        } catch (IOException e) {
            throw new AssertionError("Cannot read " + file.toAbsolutePath(), e);
        }
    }
}
