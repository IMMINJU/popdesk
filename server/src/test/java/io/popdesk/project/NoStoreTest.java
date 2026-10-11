package io.popdesk.project;

import static io.popdesk.project.ProjectChecks.assertNoStore;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Attachment;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.File;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Issue;
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
 * serves it, with path values that do not exist (404s) and, for reads and the main writes, with
 * values that do (2xx), since a controller that sets its own {@code Cache-Control} does so on success.
 */
class NoStoreTest extends BareAppTest {

    private static final Set<String> METHODS = Set.of("get", "head", "post", "put", "patch", "delete");

    /**
     * The one read whose success is not {@code no-store}: a photo answers with
     * {@code Cache-Control: private, no-cache} and an {@code ETag} (S1A-AC-87, the exception to
     * S0-AC-101). Its headers are checked by the photo tests of slice 1a. A path is one of these only
     * when the whole path is the same.
     */
    private static final Set<String> PHOTO_READS = Set.of("/api/portal/photos/{accountId}",
            "/api/console/photos/{accountId}");

    /**
     * The query a read cannot be sent without: the audit log export answers 400 without its
     * {@code format} (S1B-AC-23).
     */
    private static final Map<String, String> REQUIRED_QUERY = Map.of("/api/console/audit-log/export",
            "?format=JSONL");

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

    @Test
    @DisplayName("S0-AC-101 every GET and HEAD of both contracts, with path values that exist, answers 2xx with "
            + "Cache-Control no-store, downloads and range downloads too; a photo is the exception (S1A-AC-87)")
    void everyReadSucceedsWithNoStore() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        // The members of a company are read by its manager only (S2-AC-09), so the customer is one (S2-AC-01).
        admin.client().put("/api/console/companies/" + company.code() + "/customers/" + customer.accountId()
                + "/manager").send().expectStatus(200);
        // An agent's open assignments in a company are read for an agent assigned to it, by an admin (S3-AC-68).
        Session agent = fixtures.agent(admin, company.code());
        Issue issue = fixtures.fileIssue(customer, company.code());
        Attachment file = fixtures.attach(customer, issue.key(), File.text("nostore-" + Unique.number() + ".txt",
                "No store, read " + Unique.number()));
        fixtures.comment(admin, issue.key(), "No store answer " + Unique.number());
        Map<String, String> values = Map.of("{key}", issue.key(), "{code}", company.code(), "{id}", file.id(),
                "{accountId}", String.valueOf(agent.accountId()));
        List<String> failed = new ArrayList<>();

        for (Operation op : operations()) {
            if (!op.method().equals("GET") && !op.method().equals("HEAD")) {
                continue;
            }
            if (PHOTO_READS.contains(op.path())) {
                continue;
            }
            String path = withValues(op.path(), values) + REQUIRED_QUERY.getOrDefault(op.path(), "");
            if (op.path().startsWith("/api/portal/") || op.path().startsWith("/api/public/")) {
                checkSuccess(failed, op.method() + " " + path + " on the portal",
                        customer.client().request(op.method(), path).send());
            }
            if (op.path().startsWith("/api/console/") || op.path().startsWith("/api/public/")) {
                checkSuccess(failed, op.method() + " " + path + " on the console",
                        admin.client().request(op.method(), path).send());
            }
        }
        for (Session reader : List.of(customer, admin)) {
            String download = reader.api("/issues/" + issue.key() + "/attachments/" + file.id());
            ApiResponse range = reader.client().get(download).header("Range", "bytes=0-3").send();
            checkSuccess(failed, "range download on " + reader.app(), range);
            if (range.status() != 206) {
                failed.add("range download on " + reader.app() + " -> " + range.status() + ", not 206");
            }
        }

        assertThat(failed).as("reads that failed or had no no-store").isEmpty();
    }

    @Test
    @DisplayName("S0-AC-101 creating, assigning, uploading, commenting, deleting, and signing in and out "
            + "answer 2xx with Cache-Control no-store")
    void writesSucceedWithNoStore() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        Invitation agent = fixtures.invite(admin, Role.AGENT);
        Issue issue = fixtures.fileIssue(customer, company.code());
        List<String> failed = new ArrayList<>();
        String portalIssue = "/api/portal/issues/" + issue.key();
        String consoleIssue = "/api/console/issues/" + issue.key();
        String code = Unique.companyCode();

        expectWithNoStore(failed, 201, admin.client().post("/api/console/companies")
                .json(Map.of("code", code, "name", "Company " + code, "legalName", "Company " + code + " Ltd.")));
        expectWithNoStore(failed, 200, admin.client().put("/api/console/companies/" + company.code() + "/agents/"
                + agent.accountId()));
        expectWithNoStore(failed, 201, customer.client().post("/api/portal/issues")
                .json(Map.of("companyCode", company.code(), "title", Unique.text("No store JSON"),
                        "content", "Filed as JSON.")));
        expectWithNoStore(failed, 201, customer.client().post("/api/portal/issues")
                .jsonPart("issue", Map.of("companyCode", company.code(), "title", Unique.text("No store multipart"),
                        "content", "Filed with a file."))
                .file("files", "nostore-" + Unique.number() + ".txt", "text/plain", "filed".getBytes()));
        expectWithNoStore(failed, 201, customer.client().post(portalIssue + "/comments")
                .json(Map.of("content", "No store question " + Unique.number())));
        expectWithNoStore(failed, 201, admin.client().post(consoleIssue + "/comments")
                .json(Map.of("content", "No store answer " + Unique.number())));
        ApiResponse portalUpload = expectWithNoStore(failed, 201, customer.client().post(portalIssue + "/attachments")
                .file("file", "nostore-" + Unique.number() + ".txt", "text/plain", "portal".getBytes()));
        ApiResponse consoleUpload = expectWithNoStore(failed, 201, admin.client().post(consoleIssue + "/attachments")
                .file("file", "nostore-" + Unique.number() + ".txt", "text/plain", "console".getBytes()));
        if (portalUpload.status() == 201) {
            expectWithNoStore(failed, 204, customer.client()
                    .delete(portalIssue + "/attachments/" + portalUpload.jsonText("id")));
        }
        if (consoleUpload.status() == 201) {
            expectWithNoStore(failed, 204, admin.client()
                    .delete(consoleIssue + "/attachments/" + consoleUpload.jsonText("id")));
        }
        ApiClient browser = console();
        expectWithNoStore(failed, 204, browser.post("/api/public/sign-in")
                .json(Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD)));
        expectWithNoStore(failed, 204, browser.post("/api/public/sign-out"));

        assertThat(failed).as("writes that failed or had no no-store").isEmpty();
    }

    private static ApiResponse expectWithNoStore(List<String> failed, int status, ApiRequest request) {
        ApiResponse response = request.send();
        if (response.status() != status) {
            failed.add(response.request() + " -> " + response.status() + ", not " + status + ": "
                    + response.snippet());
        }
        checkSuccess(failed, response.request(), response);
        return response;
    }

    /** A 2xx with no-store; anything else goes to {@code failed}. */
    private static void checkSuccess(List<String> failed, String what, ApiResponse response) {
        if (response.status() < 200 || response.status() > 299) {
            failed.add(what + " -> " + response.status() + ": " + response.snippet());
        } else if (response.headers("Cache-Control").stream()
                .noneMatch(v -> v.toLowerCase(Locale.ROOT).contains("no-store"))) {
            failed.add(what + " -> " + response.status() + " Cache-Control " + response.headers("Cache-Control"));
        }
    }

    private static String withValues(String path, Map<String, String> values) {
        String out = path;
        for (Map.Entry<String, String> v : values.entrySet()) {
            out = out.replace(v.getKey(), v.getValue());
        }
        if (out.contains("{")) {
            throw new AssertionError("No value for a path variable in " + path);
        }
        return out;
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
