package io.popdesk.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.TalkFlow;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

/**
 * The inbox item of an export the app ended when it started (B2-AC-05): the {@code RUNNING} export of the last run
 * becomes {@code FAILED} with {@code INTERRUPTED}, and that gives every active admin {@code COMPANY_EXPORT_FAILED}
 * with its company code, as an export that fails while the app runs does.
 *
 * <p>This class shares the context of {@link CompanyExportRestartTest}: the same setup and the same last run, which
 * that class puts in before the app starts ({@link CompanyExportRestartTest.LastRun}). Its rows are one
 * {@code RUNNING} export and two {@code DONE} ones, all of {@link CompanyExportRestartTest#CODE}. At startup the
 * first admin is the only admin.
 */
@Import(CompanyExportRestartTest.LastRun.class)
class CompanyExportRestartAlertTest extends BareAppTest {

    static final String FAILED = "COMPANY_EXPORT_FAILED";

    /** The caller's console inbox items of {@link #FAILED}, about any company. */
    static List<JsonNode> failedItems(Session admin) {
        return TalkFlow.inbox(admin).stream().filter(n -> FAILED.equals(TalkFlow.kind(n))).toList();
    }

    @Test
    @DisplayName("B2-AC-05 when the app starts and turns the RUNNING export of the last run FAILED, the admin gets one "
            + "COMPANY_EXPORT_FAILED with that company code and no issue; the DONE exports it kept or expired give "
            + "none")
    void interruptedExportAlerts() {
        assertThat(CompanyExportRestartTest.seedProblem).as("what went wrong putting the last run in").isNull();
        Session admin = fixtures.signInAdmin();

        List<JsonNode> items = Wait.until("a " + FAILED + " item in the inbox of " + admin.email(), () -> {
            List<JsonNode> now = failedItems(admin);
            return now.isEmpty() ? null : now;
        });

        assertThat(items).as("%s items of the admin", FAILED).hasSize(1);
        JsonNode item = items.getFirst();
        assertThat(item.path("companyCode").asString(null)).as("companyCode of %s", item)
                .isEqualTo(CompanyExportRestartTest.CODE);
        assertThat(TalkFlow.isNull(item, "issue")).as("issue of %s is null", item).isTrue();
    }
}
