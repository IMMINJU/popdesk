package io.popdesk.audit;

import static io.popdesk.audit.AuditSteps.EXPORT;
import static io.popdesk.audit.AuditSteps.LOG;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Who may read the audit log and run the integrity check: admins only. The portal has no such path
 * at all.
 *
 * <p>A customer has no session the console counts: on the portal host every console path is 404
 * (S0-AC-13), and a customer's session cookie sent to the console host is not a session there, so
 * the answer is 401, as without a session. Neither is 403; the criterion's 403 is the agent's.
 */
class AuditLogAccessTest extends BareAppTest {

    /** Who asks, without being a signed-in member of staff. */
    enum Asking {
        NOBODY,
        CUSTOMER_ON_THE_PORTAL_HOST,
        CUSTOMER_COOKIES_ON_THE_CONSOLE_HOST
    }

    /** The four operations of the audit log. */
    enum Operation {
        LIST,
        EXPORT,
        EXPORT_HEAD,
        VERIFY;

        ApiRequest on(ApiClient client) {
            return switch (this) {
                case LIST -> client.get(AuditSteps.LOG);
                case EXPORT -> client.get(AuditSteps.EXPORT).query("format", "JSONL");
                case EXPORT_HEAD -> client.head(AuditSteps.EXPORT).query("format", "JSONL");
                case VERIFY -> client.post(ChainSteps.VERIFY);
            };
        }
    }

    private ApiClient browserOf(Asking asking) {
        if (asking == Asking.NOBODY) {
            ApiClient nobody = console();
            nobody.get(ApiClient.CSRF_PATH).expect(204);
            return nobody;
        }
        Session admin = fixtures.signInAdmin();
        Session customer = fixtures.customer(admin, fixtures.createCompany(admin).code());
        if (asking == Asking.CUSTOMER_ON_THE_PORTAL_HOST) {
            return customer.client();
        }
        ApiClient carried = console();
        customer.client().cookies().forEach(carried::setCookie);
        return carried;
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
        "NOBODY, LIST", "NOBODY, EXPORT", "NOBODY, EXPORT_HEAD", "NOBODY, VERIFY",
        "CUSTOMER_ON_THE_PORTAL_HOST, LIST", "CUSTOMER_ON_THE_PORTAL_HOST, EXPORT",
        "CUSTOMER_ON_THE_PORTAL_HOST, EXPORT_HEAD", "CUSTOMER_ON_THE_PORTAL_HOST, VERIFY",
        "CUSTOMER_COOKIES_ON_THE_CONSOLE_HOST, LIST", "CUSTOMER_COOKIES_ON_THE_CONSOLE_HOST, EXPORT",
        "CUSTOMER_COOKIES_ON_THE_CONSOLE_HOST, EXPORT_HEAD", "CUSTOMER_COOKIES_ON_THE_CONSOLE_HOST, VERIFY"
    })
    @DisplayName("S1B-AC-02, S1B-AC-18 without a session the audit log, its export, and the integrity check are "
            + "401 UNAUTHENTICATED; a customer gets 404 NOT_FOUND on the portal host and, with the session "
            + "cookies carried to the console host, 401; no answer is a file")
    void neitherNobodyNorACustomerGetsThrough(Asking asking, Operation operation) {
        ApiClient browser = browserOf(asking);

        ApiResponse answer = operation.on(browser).send();

        boolean notFound = asking == Asking.CUSTOMER_ON_THE_PORTAL_HOST;
        assertThat(answer.status()).as("%s: %s", answer, answer.snippet()).isEqualTo(notFound ? 404 : 401);
        assertThat(answer.header("Content-Disposition")).as("Content-Disposition of %s", answer).isNull();
        if (operation == Operation.EXPORT_HEAD) {
            assertThat(answer.body()).as("body of the HEAD").isEmpty();
        } else {
            assertThat(answer.errorCode()).as("code of %s", answer).isEqualTo(notFound ? "NOT_FOUND"
                    : "UNAUTHENTICATED");
        }
    }

    @Test
    @DisplayName("S1B-AC-02 an agent asking for the audit log or its export gets 403 FORBIDDEN")
    void agentForbidden() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session agent = fixtures.agent(admin, company.code());

        ApiResponse list = agent.client().get(LOG).send();
        ApiResponse export = agent.client().get(EXPORT).query("format", "JSONL").send();
        ApiResponse exportHead = agent.client().head(EXPORT).query("format", "JSONL").send();

        assertThat(list.status()).as(list.request()).isEqualTo(403);
        assertThat(list.errorCode()).isEqualTo("FORBIDDEN");
        assertThat(list.text()).as("body of the 403").doesNotContain(company.code(), admin.email());
        assertThat(export.status()).as(export.request()).isEqualTo(403);
        assertThat(export.errorCode()).isEqualTo("FORBIDDEN");
        assertThat(export.header("Content-Disposition")).as("Content-Disposition of the 403").isNull();
        assertThat(exportHead.status()).as(exportHead.request()).isEqualTo(403);
        assertThat(exportHead.body()).as("body of the HEAD").isEmpty();
    }

    @Test
    @DisplayName("S1B-AC-02 the portal has no audit log: the portal contract has no such path, and a customer "
            + "asking for /api/portal/audit-log gets 404")
    void portalHasNoAuditLog() {
        Session admin = fixtures.signInAdmin();
        Session customer = fixtures.customer(admin, fixtures.createCompany(admin).code());
        JsonNode paths = read(Path.of("..", "docs", "integrate", "openapi-portal.json")).path("paths");

        ApiResponse list = customer.client().get("/api/portal/audit-log").send();
        ApiResponse export = customer.client().get("/api/portal/audit-log/export").query("format", "JSONL").send();

        assertThat(paths.propertyNames()).as("paths of the portal contract").isNotEmpty()
                .noneMatch(path -> path.contains("audit"));
        assertThat(list.status()).as(list.request()).isEqualTo(404);
        assertThat(list.errorCode()).isEqualTo("NOT_FOUND");
        assertThat(export.status()).as(export.request()).isEqualTo(404);
    }

    private static JsonNode read(Path file) {
        try {
            return JsonMapper.builder().build().readTree(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file.toAbsolutePath(), e);
        }
    }
}
