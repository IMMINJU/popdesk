package io.popdesk.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Without {@code AUDIT_FORWARD_URL} (setup "bare") an audit entry puts nothing in the send queue for
 * the outside copy. The queue is read in the database: the {@code outbox} table and its
 * {@code channel} column are there since the first slice, and the copy's channel is
 * {@code AUDIT_FORWARD}, as the failed list names it.
 */
class AuditForwardOffTest extends BareAppTest {

    @Test
    @DisplayName("S1B-AC-31 without AUDIT_FORWARD_URL an audit entry leaves no copy row in the send queue, and "
            + "the status shows the copy off with nothing queued")
    void noAddressNoQueueRow() {
        Session admin = fixtures.signInAdmin();

        Company company = fixtures.createCompany(admin);

        assertThat(db.count("select count(*) from audit_log where action = 'COMPANY_CREATED' "
                + "and target ->> 'code' = ?", company.code())).as("audit entries of creating %s", company.code())
                .isEqualTo(1);
        Wait.holds("no row of the copy channel in the send queue", Duration.ofSeconds(3),
                () -> db.count("select count(*) from outbox where channel = 'AUDIT_FORWARD'") == 0);
        JsonNode copy = admin.client().get("/api/console/system/status").expect(200).json().path("auditForward");
        assertThat(copy.path("enabled").isBoolean()).as("auditForward.enabled is a boolean: %s", copy).isTrue();
        assertThat(copy.path("enabled").asBoolean()).as("auditForward.enabled").isFalse();
        assertThat(copy.path("queued").asLong(-1)).as("auditForward.queued").isZero();
    }
}
