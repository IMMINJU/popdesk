package io.popdesk.auth;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** First admin password: 73 characters. */
class FirstAdminPasswordSeventyThreeCharactersTest extends FirstAdminPasswordRejectedBase {

    @DynamicPropertySource
    static void adminPassword(DynamicPropertyRegistry registry) {
        registry.add("POPDESK_ADMIN_PASSWORD", () -> "P".repeat(36) + "-".repeat(36) + "1");
    }
}
