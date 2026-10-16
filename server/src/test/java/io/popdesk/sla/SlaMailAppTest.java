package io.popdesk.sla;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.notify.Delivery.FakeSmtp;
import io.popdesk.support.ConnectedAppTest;
import java.time.Duration;
import java.time.ZoneId;
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
 * The app of the SLA tests that read mail and webhook messages (slice 8b): "connected" ({@link ConnectedAppTest}),
 * with {@code POPDESK_TIMEZONE=Asia/Seoul} as in {@link SlaAppTest}, and a context and database of its own, so the
 * one SLA policy of the installation reaches no other class. The installation language is the default {@code en};
 * an account's mail is in its own language ({@link SlaAlertSteps#language}).
 *
 * <p>SMTP goes through {@link FakeSmtp} (then GreenMail), so a test can refuse mail for a while
 * ({@link #holdMail()}): the app's first attempt fails, the row stays in the queue, and the retry comes a second
 * later on the app clock ({@link #letMailGo()}). Mail passes before and after each test.
 *
 * <p>As in {@link SlaAppTest}, a test first sets the whole policy it needs and counts only its own issues.
 */
@TestPropertySource(properties = {"POPDESK_TIMEZONE=" + SlaAppTest.SEOUL, SlaMailAppTest.OWN_CONTEXT})
@Import(SlaMailAppTest.HeldSmtp.class)
public abstract class SlaMailAppTest extends ConnectedAppTest {

    /** A property the app does not read; it keeps this context apart from the other connected ones. */
    public static final String OWN_CONTEXT = "sla.test.context=sla-mail";

    /**
     * Points {@code SMTP_PORT} at {@link FakeSmtp}. A registrar bean is applied after the
     * {@code @DynamicPropertySource} of {@link ConnectedAppTest} and wins over it.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class HeldSmtp {

        @Bean
        static DynamicPropertyRegistrar slaSmtpPort() {
            return registry -> registry.add("SMTP_PORT", () -> FakeSmtp.shared().port());
        }
    }

    @Autowired
    private Environment environment;

    protected SlaSteps sla;
    protected SlaAlertSteps alerts;
    protected IssueFlow flow;

    protected ZoneId zone() {
        return ZoneId.of(SlaAppTest.SEOUL);
    }

    @BeforeEach
    void setUpSlaMail() {
        assertThat(environment.getProperty("SMTP_PORT")).as("SMTP_PORT of this context")
                .isEqualTo(String.valueOf(FakeSmtp.shared().port()));
        FakeSmtp.shared().reset();
        flow = new IssueFlow(fixtures, clock);
        sla = new SlaSteps(fixtures, clock, zone());
        alerts = new SlaAlertSteps(fixtures, clock, sla);
    }

    @AfterEach
    void passMailAgain() {
        FakeSmtp.shared().pass();
        webhook().respondWith(204);
    }

    /** From now on, the mail server refuses every message at its end with 451 and keeps it ({@link #heldTo}). */
    protected void holdMail() {
        FakeSmtp.shared().refuseMessage("451 4.3.0 Held by the test, try again later");
    }

    /** Waits for a refused attempt at {@code address}. */
    protected FakeSmtp.Taken heldTo(String address) {
        return FakeSmtp.shared().awaitTakenTo(address);
    }

    /** Lets mail through again and moves the clock the second after which the queue tries again. */
    protected void letMailGo() {
        FakeSmtp.shared().pass();
        clock.advance(Duration.ofSeconds(1));
    }
}
