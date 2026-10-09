package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.deploy.AppSteps.Person;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SMTP on port 465 uses TLS from the first byte (ADR D-51). The app image runs next to a GreenMail
 * container ({@code smtps.compose.yml}) that speaks SMTPS on 465 and plain SMTP without STARTTLS on
 * 3025. The JVM trusts the mail server's test certificate through its default trust store
 * ({@code -Djavax.net.ssl.trustStore} in {@code JDK_JAVA_OPTIONS}); the app gets no mail TLS setting
 * of its own (tasks.md section 5). The app in the test JVM cannot be tested this way: its mail
 * servers listen on random ports, never on 465.
 */
@Tag("deploy")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ImplicitTlsMailTest {

    private static final String TRUST = "-Djavax.net.ssl.trustStore=/popdesk-test/mail-trust.p12 "
            + "-Djavax.net.ssl.trustStorePassword=popdesk-test -Djavax.net.ssl.trustStoreType=PKCS12";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Once<Stack> RUNNING = new Once<>("starting the app with GreenMail on 465",
            ImplicitTlsMailTest::start);

    @AfterAll
    static void stop() {
        Stack stack = RUNNING.peek();
        if (stack != null) {
            stack.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("S0-AC-74 with SMTP_PORT 465, an invitation mail reaches the mail server over implicit TLS")
    void port465UsesImplicitTls() {
        Stack stack = RUNNING.get();
        AppSteps steps = new AppSteps(stack);
        Person admin = steps.signInAdmin();
        String company = steps.createCompany(admin);
        String email = Unique.email("smtps");

        steps.invite(admin, "CUSTOMER", email, List.of(company));

        JsonNode messages = Wait.until("mail to " + email + " on the SMTPS server", Duration.ofSeconds(60),
                () -> {
                    JsonNode m = messagesTo(stack, email);
                    return m == null || m.isEmpty() ? null : m;
                });
        assertThat(messages.size()).as("mails to %s", email).isEqualTo(1);
    }

    @Test
    @Order(2)
    @DisplayName("S0-AC-74 pointed at the same server's plain port that has no STARTTLS, the app sends nothing")
    void plainPortWithoutStartTlsSendsNothing() {
        Stack stack = RUNNING.get();
        stack.appEnv("SMTP_PORT", "3025").withoutAppEnv("SMTP_ALLOW_PLAINTEXT");
        stack.up("app");
        stack.awaitHealthy("app");
        List<String> env = stack.inspect("app").path("Config").path("Env").valueStream()
                .map(n -> n.asString("")).toList();
        assertThat(env).as("app environment after recreating it").contains("SMTP_PORT=3025")
                .noneMatch(e -> e.startsWith("SMTP_ALLOW_PLAINTEXT="));
        AppSteps steps = new AppSteps(stack);
        Person admin = steps.signInAdmin();
        String company = steps.createCompany(admin);
        String email = Unique.email("plain");

        steps.invite(admin, "CUSTOMER", email, List.of(company));

        Wait.holds("no mail to " + email + " over plain SMTP", Duration.ofSeconds(15),
                () -> {
                    JsonNode m = messagesTo(stack, email);
                    return m != null && m.isEmpty();
                });
    }

    private static Stack start() {
        Stack stack = Stack.release("smtps").withResource("smtps.compose.yml");
        try {
            stack.appEnv("SMTP_HOST", "mail")
                    .appEnv("SMTP_PORT", "465")
                    .appEnv("SMTP_USER", "popdesk")
                    .appEnv("SMTP_PASSWORD", "mail-password")
                    .appEnv("MAIL_FROM", "popdesk@popdesk.test")
                    .appEnv("JDK_JAVA_OPTIONS", TRUST);
            stack.up();
            stack.awaitHealthy("app");
            return stack;
        } catch (AssertionError | RuntimeException e) {
            stack.close();
            throw e;
        }
    }

    /**
     * Mail GreenMail received for an address: empty when it has none (no mailbox yet), null when the
     * API did not answer.
     */
    private static JsonNode messagesTo(Stack stack, String email) {
        String url = "http://127.0.0.1:" + stack.port("mail", 8080) + "/api/user/" + email + "/messages/";
        Http.Response r = Http.attempt(url, null, null);
        if (r == null || r.status() >= 500) {
            return null;
        }
        if (r.status() != 200) {
            return JSON.createArrayNode();
        }
        return JSON.readTree(r.body());
    }
}
