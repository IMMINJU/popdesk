package io.popdesk.auth;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** First admin password: only whitespace. */
class FirstAdminPasswordOnlyWhitespaceTest extends FirstAdminPasswordRejectedBase {

    @DynamicPropertySource
    static void adminPassword(DynamicPropertyRegistry registry) {
        registry.add("POPDESK_ADMIN_PASSWORD", () -> " ".repeat(12));
    }
}
