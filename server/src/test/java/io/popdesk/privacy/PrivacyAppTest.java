package io.popdesk.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.notify.Delivery.FakeSmtp;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.WebhookReceiver;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The app of every server test that anonymizes an account (slice 10a): the audit chain and "the last admin" are one
 * per installation, so these tests run in a context and a database of their own.
 *
 * <ul>
 *   <li>"connected" ({@link ConnectedAppTest}), with SMTP through {@link FakeSmtp} (then GreenMail), so a test can
 *       keep mail in the send queue for a while ({@link #holdMail()}, {@link #letMailGo()}).
 *   <li>The outside copy of the audit log (AUDIT-9) on: {@code AUDIT_FORWARD_URL} points at the
 *       {@link WebhookReceiver} on {@link #COPY_PATH} with {@link #COPY_TOKEN}. A request the receiver got can be a
 *       copy or a webhook; read copies by their path ({@link PrivacySteps#copies}).
 *   <li>The installation language is {@code ko}, so the anonymous name is {@value #ANONYMOUS_NAME}.
 *   <li>Before each test there are at least {@link #ADMINS} active admins, so no test meets the last-admin check by
 *       chance. The last-admin case itself runs in a context of its own ({@link LastAdminAnonymizeTest}).
 * </ul>
 *
 * <p>Tests share the one audit chain: they look only at entries found by the IDs of accounts they made, and of the
 * integrity check only at "all entries match". Mail passes before and after each test.
 */
@TestPropertySource(properties = {PrivacyAppTest.OWN_CONTEXT, "POPDESK_LOCALE=ko",
        "AUDIT_FORWARD_TOKEN=" + PrivacyAppTest.COPY_TOKEN})
@Import(PrivacyAppTest.Wiring.class)
public abstract class PrivacyAppTest extends ConnectedAppTest {

    /** A property the app does not read; it keeps this context apart from the other connected ones. */
    public static final String OWN_CONTEXT = "privacy.test.context=privacy";

    /** The anonymous name in the installation language {@code ko} (acceptance.md 2절 "말의 뜻"). */
    public static final String ANONYMOUS_NAME = "삭제된 사용자";

    public static final String COPY_PATH = "/privacy-audit-copy";
    public static final String COPY_TOKEN = "privacy-Copy-Token-7d21";

    /** Active admins there are at least before each test, the first admin among them. */
    public static final int ADMINS = 3;

    /**
     * Points {@code SMTP_PORT} at {@link FakeSmtp} and the outside copy at the receiver. A registrar bean is applied
     * after the {@code @DynamicPropertySource} of {@link ConnectedAppTest} and wins over it.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class Wiring {

        @Bean
        static DynamicPropertyRegistrar privacySettings() {
            return registry -> {
                registry.add("SMTP_PORT", () -> FakeSmtp.shared().port());
                registry.add("AUDIT_FORWARD_URL", PrivacyAppTest::copyUrl);
            };
        }
    }

    static String copyUrl() {
        return "http://127.0.0.1:" + WebhookReceiver.shared().port() + COPY_PATH;
    }

    @Autowired
    private Environment environment;

    /** This context's database container, for tests that hold or end a statement of the app. */
    @Autowired
    protected PostgreSQLContainer postgres;

    /** Issue steps of the earlier slices (assignee, status, filing on behalf, suspending). */
    protected IssueFlow flow;

    @BeforeEach
    void setUpPrivacy() {
        assertThat(environment.getProperty("SMTP_PORT")).as("SMTP_PORT of this context")
                .isEqualTo(String.valueOf(FakeSmtp.shared().port()));
        FakeSmtp.shared().reset();
        flow = new IssueFlow(fixtures, clock);
        Session admin = fixtures.signInAdmin();
        for (long n = PrivacySteps.activeAdmins(admin); n < ADMINS; n++) {
            fixtures.admin(admin);
        }
    }

    @AfterEach
    void passMailAgain() {
        FakeSmtp.shared().pass();
        webhook().respondWith(204);
    }

    /** From now on, the mail server refuses every message at its end with 451; the rows stay in the send queue. */
    protected void holdMail() {
        FakeSmtp.shared().refuseMessage("451 4.3.0 Held by the test, try again later");
    }

    /** Lets mail through again and moves the clock the second after which the queue tries again. */
    protected void letMailGo() {
        FakeSmtp.shared().pass();
        clock.advance(Duration.ofSeconds(1));
    }
}
