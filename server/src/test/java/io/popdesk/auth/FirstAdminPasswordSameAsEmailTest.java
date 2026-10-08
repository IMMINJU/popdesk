package io.popdesk.auth;

import io.popdesk.support.AppTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** First admin password: the same as POPDESK_ADMIN_EMAIL. */
class FirstAdminPasswordSameAsEmailTest extends FirstAdminPasswordRejectedBase {

    @DynamicPropertySource
    static void adminPassword(DynamicPropertyRegistry registry) {
        registry.add("POPDESK_ADMIN_PASSWORD", () -> AppTest.ADMIN_EMAIL);
    }
}
