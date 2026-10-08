package io.popdesk.auth;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** First admin password: 9 characters. */
class FirstAdminPasswordNineCharactersTest extends FirstAdminPasswordRejectedBase {

    @DynamicPropertySource
    static void adminPassword(DynamicPropertyRegistry registry) {
        registry.add("POPDESK_ADMIN_PASSWORD", () -> "Nine-ch1x");
    }
}
