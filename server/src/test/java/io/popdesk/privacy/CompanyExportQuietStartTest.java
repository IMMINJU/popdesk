package io.popdesk.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.TalkFlow;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A start with no export to end leaves no {@code COMPANY_EXPORT_FAILED} (B2-AC-05). The shared "bare" context started
 * on an empty database, and no test of that context asks for a company export (they run in
 * {@link CompanyExportAppTest}), so the first admin, who was there at startup, has no such item.
 */
class CompanyExportQuietStartTest extends BareAppTest {

    @Test
    @DisplayName("B2-AC-05 when the app starts with no RUNNING export it changes none and the first admin gets no "
            + "COMPANY_EXPORT_FAILED")
    void noExportNoAlert() {
        Session admin = fixtures.signInAdmin();

        assertThat(TalkFlow.inbox(admin)).as("inbox of %s", admin.email())
                .noneMatch(n -> CompanyExportRestartAlertTest.FAILED.equals(TalkFlow.kind(n)));
    }
}
