package io.popdesk.support;

import io.popdesk.TestcontainersConfiguration;
import io.popdesk.support.Fixtures.App;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * The app on a real port with its own PostgreSQL 16 container, configured only through its setting
 * keys (docs/operate/configuration.md). Do not extend this class; extend one of the named setups,
 * which share test contexts:
 *
 * <ul>
 *   <li>{@link BareAppTest}: no SMTP, no webhook.
 *   <li>{@link ConnectedAppTest}: SMTP to GreenMail in plain text ({@code SMTP_ALLOW_PLAINTEXT=true}),
 *       webhook to {@link WebhookReceiver}.
 *   <li>{@link StartTlsAppTest}: SMTP to a STARTTLS server, plain text not allowed; webhook on.
 * </ul>
 *
 * Change a key for one test class with {@code @TestPropertySource(properties = "KEY=value")}. That
 * starts another context and another database, so prefer a named setup.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "POPDESK_PORTAL_HOST=" + AppTest.PORTAL_HOST,
        "POPDESK_CONSOLE_HOST=" + AppTest.CONSOLE_HOST,
        "POPDESK_PUBLIC_SCHEME=http",
        "POPDESK_PUBLIC_PORT=80",
        "POPDESK_ADMIN_EMAIL=" + AppTest.ADMIN_EMAIL,
        "POPDESK_ADMIN_PASSWORD=" + AppTest.ADMIN_PASSWORD,
})
@Import({TestcontainersConfiguration.class, SupportConfiguration.class})
public abstract class AppTest {

    public static final String PORTAL_HOST = "portal.popdesk.test";
    public static final String CONSOLE_HOST = "console.popdesk.test";
    public static final String ADMIN_EMAIL = "admin@popdesk.test";
    public static final String ADMIN_PASSWORD = "admin-Password-1";

    /** The app's HTTP port (8080 in production). */
    @LocalServerPort
    protected int port;

    /** The app's clock; stands still until a test moves it. */
    @Autowired
    protected TestClock clock;

    /** The app's standard output and error since this context started. */
    @Autowired
    protected AppLog log;

    /** Read-only look into this context's database. */
    @Autowired
    protected DbInspector db;

    /** Test data through the API. */
    protected Fixtures fixtures;

    AppTest() {}

    @BeforeEach
    void setUpSupport() {
        fixtures = new Fixtures(this);
    }

    /** A new browser on the portal host. */
    protected ApiClient portal() {
        return client(PORTAL_HOST);
    }

    /** A new browser on the console host. */
    protected ApiClient console() {
        return client(CONSOLE_HOST);
    }

    /** A new client sending {@code Host: host}, for example a host the app does not know. */
    protected ApiClient client(String host) {
        return new ApiClient(host, port);
    }

    protected ApiClient client(App app) {
        return app == App.PORTAL ? portal() : console();
    }

    /** Whether this setup configures SMTP. Fixtures then read invitation links from mail. */
    protected abstract boolean smtpOn();

    /** The mail servers. Running in every setup, but the app sends to them only when SMTP is on. */
    protected MailServer mail() {
        return MailServer.shared();
    }

    /** The webhook receiver. Running in every setup, but the app posts to it only when the webhook is on. */
    protected WebhookReceiver webhook() {
        return WebhookReceiver.shared();
    }
}
