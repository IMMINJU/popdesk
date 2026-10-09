package io.popdesk.boundary;

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

/**
 * Setup of this package's mail tests: "connected", but SMTP goes through {@link SmtpGate}, which a
 * test can close to keep a mail in the send queue. Classes that extend this share one context. The
 * gate is open before and after each test.
 *
 * <p>The same as the notify package's setup of the same name, without its {@code POPDESK_LOCALE};
 * the notify one is package-private, so this package has its own and its own context.
 */
@Import(GatedMailTest.GatedSmtp.class)
abstract class GatedMailTest extends ConnectedAppTest {

    /**
     * Points {@code SMTP_PORT} at the gate. A {@code @DynamicPropertySource} method here would lose to
     * the one in {@link ConnectedAppTest}; a registrar bean is applied after it and wins.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class GatedSmtp {

        @Bean
        static DynamicPropertyRegistrar boundaryGatedSmtpPort() {
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
