package io.popdesk.audit;

import static io.popdesk.audit.AuditSteps.EXPORT;
import static io.popdesk.audit.AuditSteps.LOG;
import static org.assertj.core.api.Assertions.assertThat;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Who may read the audit log: admins only. The portal has no such path at all. */
class AuditLogAccessTest extends BareAppTest {

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
