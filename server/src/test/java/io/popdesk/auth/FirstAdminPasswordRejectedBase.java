package io.popdesk.auth;

import static io.popdesk.auth.AuthChecks.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.AppTest;
import io.popdesk.support.BareAppTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A first admin password that breaks AUTH-5. The app then creates no admin and takes no
 * requests: 503 {@code SETUP_REQUIRED} (conventions). Each subclass sets one such password
 * with {@code @DynamicPropertySource}, which keeps leading spaces as they are, and so runs in its own
 * context.
 */
abstract class FirstAdminPasswordRejectedBase extends BareAppTest {

    @Test
    @DisplayName("S0-AC-28 a first admin password that breaks the rules creates no admin")
    void noFirstAdmin() {
        assertError(console().get("/api/console/me").send(), 503, "SETUP_REQUIRED");
        assertThat(AuthChecks.rowsContaining(db, AppTest.ADMIN_EMAIL)).as("rows holding the admin email").isEmpty();
    }
}
