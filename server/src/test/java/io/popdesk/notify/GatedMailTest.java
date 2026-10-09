package io.popdesk.notify;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ConnectedAppTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.TestPropertySource;

/**
 * Setup of this package: "connected", but SMTP goes through {@link SmtpGate}, which a test can
 * close to make sending fail, and the installation language is Korean ({@code POPDESK_LOCALE=ko}),
 * so mail in the recipient's language can be told from the default {@code en}.
 *
 * <p>Classes that extend this share one context. The gate is open before and after each test.
 */
@TestPropertySource(properties = "POPDESK_LOCALE=ko")
@Import(GatedMailTest.GatedSmtp.class)
abstract class GatedMailTest extends ConnectedAppTest {

    /**
     * Points {@code SMTP_PORT} at the gate. A {@code @DynamicPropertySource} method here would lose to
     * the one in {@link ConnectedAppTest}; a registrar bean is applied after it and wins.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class GatedSmtp {

        @Bean
        static DynamicPropertyRegistrar gatedSmtpPort() {
            return registry -> registry.add("SMTP_PORT", () -> SmtpGate.shared().port());
        }
    }

    @Autowired
    private Environment environment;

    protected SmtpGate gate() {
        return SmtpGate.shared();
    }

    @BeforeEach
    void openGate() {
        assertThat(environment.getProperty("SMTP_PORT")).as("SMTP_PORT of this context")
                .isEqualTo(String.valueOf(gate().port()));
        gate().reset();
    }

    @AfterEach
    void reopenGate() {
        gate().pass();
    }
}
