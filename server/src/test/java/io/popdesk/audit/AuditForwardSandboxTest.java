package io.popdesk.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The outside copy in a sandbox (D-46): with {@code POPDESK_SANDBOX=true} the copy is off although
 * {@code AUDIT_FORWARD_URL} is set.
 *
 * <p>A context of its own: the keys of {@link AuditForwardTest} and {@code POPDESK_SANDBOX=true}, the
 * one difference. The queue is read in the database, as {@link AuditForwardOffTest} reads it. The
 * receiver is shared with the context of {@link AuditForwardTest}, so this test looks only for
 * requests that hold the code of the company it made.
 */
@TestPropertySource(properties = {"AUDIT_FORWARD_TOKEN=" + AuditForwardTest.TOKEN, "POPDESK_SANDBOX=true"})
class AuditForwardSandboxTest extends BareAppTest {

    @DynamicPropertySource
    static void outsideCopy(DynamicPropertyRegistry registry) {
        registry.add("AUDIT_FORWARD_URL", AuditForwardTest::copyUrl);
    }

    @Test
    @DisplayName("S1B-AC-31 in a sandbox an audit entry is not copied although AUDIT_FORWARD_URL is set: the "
            + "receiver gets no request, the send queue gets no copy row, and the status shows the copy off")
    void sandboxSendsNoCopy() {
        Session admin = fixtures.signInAdmin();

        Company company = fixtures.createCompany(admin);

        assertThat(db.count("select count(*) from audit_log where action = 'COMPANY_CREATED' "
                + "and target ->> 'code' = ?", company.code())).as("audit entries of creating %s", company.code())
                .isEqualTo(1);
        Wait.holds("no request that holds the company's code and no row of the copy channel in the send queue",
                Duration.ofSeconds(3),
                () -> webhook().containing(company.code()).isEmpty()
                        && db.count("select count(*) from outbox where channel = 'AUDIT_FORWARD'") == 0);
        JsonNode copy = admin.client().get("/api/console/system/status").expect(200).json().path("auditForward");
        assertThat(copy.path("enabled").isBoolean()).as("auditForward.enabled is a boolean: %s", copy).isTrue();
        assertThat(copy.path("enabled").asBoolean()).as("auditForward.enabled").isFalse();
    }
}
