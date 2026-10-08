package io.popdesk.project;

import static io.popdesk.project.ProjectChecks.rowsWithText;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * An invited account's language is {@code POPDESK_LOCALE}. The default is {@code en}, so this
 * class sets {@code ko} to tell the setting from a fixed default. The API does not show an
 * account's language, so the test reads the account row: the row that holds the email must also
 * hold the language.
 */
@TestPropertySource(properties = "POPDESK_LOCALE=ko")
class InvitationLocaleTest extends BareAppTest {

    private static final Set<String> KOREAN = Set.of("ko", "ko-KR", "ko_KR");

    @Test
    @DisplayName("S0-AC-33 an invited account's language is POPDESK_LOCALE")
    void accountLanguageIsInstallationLocale() {
        Session admin = fixtures.signInAdmin();
        String email = Unique.email("locale");

        InvitationTest.invite(admin, email, "Locale", "AGENT", List.of()).expectStatus(201);

        List<Map<String, Object>> rows = rowsWithText(db, email);
        assertThat(rows).as("rows that hold " + email).isNotEmpty();
        assertThat(rows).as("a row with " + email + " and the language ko")
                .anySatisfy(row -> assertThat(row.values()).anyMatch(v -> v != null && KOREAN.contains(v.toString())));
    }
}
