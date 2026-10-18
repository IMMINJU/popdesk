package io.popdesk.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * An export is recorded once in the audit log (S10A-AC-06, AUDIT-1); the {@code HEAD} the console sends first is
 * not (the contract of {@code headAccountExport}).
 */
class ExportAuditTest extends BareAppTest {

    /** Every {@code ACCOUNT_EXPORTED} entry whose target is the account. */
    private static List<JsonNode> exported(Session admin, long accountId) {
        JsonNode page = admin.client().get("/api/console/audit-log").query("action", "ACCOUNT_EXPORTED")
                .query("targetAccountId", accountId).query("size", 100).expect(200).json();
        List<JsonNode> out = new ArrayList<>();
        page.path("items").forEach(out::add);
        return out;
    }

    @Test
    @DisplayName("S10A-AC-06 an export leaves one ACCOUNT_EXPORTED entry by the admin, target {type: ACCOUNT, "
            + "accountId}, no changes; the HEAD before it leaves none")
    void oneEntryPerExport() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());

        ExportSteps.headRequest(admin, customer.accountId()).expect(200);
        List<JsonNode> afterHead = exported(admin, customer.accountId());
        ExportSteps.export(admin, customer.accountId());
        List<JsonNode> afterExport = exported(admin, customer.accountId());

        assertThat(afterHead).as("ACCOUNT_EXPORTED entries after HEAD").isEmpty();
        assertThat(afterExport).as("ACCOUNT_EXPORTED entries after the export").singleElement().satisfies(entry -> {
            assertThat(entry.path("actor").path("accountId").asLong(-1)).as("actor of %s", entry)
                    .isEqualTo(admin.accountId());
            JsonNode target = entry.path("target");
            assertThat(new ArrayList<>(target.propertyNames())).as("target of %s", entry)
                    .containsExactlyInAnyOrder("type", "accountId");
            assertThat(target.path("type").asString(null)).as("target.type").isEqualTo("ACCOUNT");
            assertThat(target.path("accountId").asLong(-1)).as("target.accountId").isEqualTo(customer.accountId());
            assertThat(entry.has("changes") && entry.path("changes").isNull()).as("changes is null in %s", entry)
                    .isTrue();
        });
    }
}
