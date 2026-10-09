package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.deploy.AppSteps.Person;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The development Compose file, {@code deploy/compose.dev.yml}, laid over the release file: it adds
 * Mailpit and points the app's SMTP at it, in plain text (ADR D-51, acceptance section 5 item 9).
 */
@Tag("deploy")
class DevComposeTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("S0-AC-90 the release Compose file has no Mailpit")
    void releaseHasNoMailpit() {
        ComposeModel file = ComposeModel.read(Repo.releaseCompose());
        ComposeModel resolved;
        try (Stack stack = Stack.of("dev", Repo.releaseCompose())) {
            resolved = ComposeModel.resolved(stack.config());
        }

        for (ComposeModel compose : List.of(file, resolved)) {
            assertThat(compose.services().keySet()).as("services in %s", compose.origin()).doesNotContain("mailpit");
            for (String service : compose.services().keySet()) {
                assertThat(String.valueOf(compose.image(service))).as("image of %s in %s", service, compose.origin())
                        .doesNotContainIgnoringCase("mailpit");
            }
        }
    }

    @Test
    @DisplayName("S0-AC-90 the dev file adds Mailpit and points SMTP at it with SMTP_ALLOW_PLAINTEXT=true")
    void devPointsSmtpAtMailpit() {
        ComposeModel compose;
        try (Stack stack = Stack.of("dev", Repo.releaseCompose(), Repo.devCompose())) {
            compose = ComposeModel.resolved(stack.config());
        }

        String mailpit = mailpitService(compose);
        Map<String, Object> app = compose.environment("app");
        assertThat(app.get("SMTP_HOST")).as("app SMTP_HOST").isEqualTo(mailpit);
        assertThat(String.valueOf(app.get("SMTP_PORT"))).as("app SMTP_PORT").isEqualTo(Integer.toString(smtpPort(compose, mailpit)));
        assertThat(String.valueOf(app.get("SMTP_ALLOW_PLAINTEXT"))).as("app SMTP_ALLOW_PLAINTEXT").isEqualTo("true");
    }

    @Test
    @DisplayName("S0-AC-90 started with the dev file, an invitation mail reaches Mailpit")
    void invitationReachesMailpit() {
        try (Stack stack = Stack.release("dev").with(Repo.devCompose())) {
            String mailpit = mailpitService(ComposeModel.resolved(stack.config()));
            stack.publish(mailpit, 8025).up();
            stack.awaitHealthy("app");
            AppSteps steps = new AppSteps(stack);
            Person admin = steps.signInAdmin();
            String company = steps.createCompany(admin);
            String email = Unique.email("dev");

            steps.invite(admin, "CUSTOMER", email, List.of(company));

            String api = "http://127.0.0.1:" + stack.port(mailpit, 8025) + "/api/v1/messages";
            JsonNode message = Wait.until("invitation mail to " + email + " in Mailpit", Duration.ofSeconds(60),
                    () -> {
                        Http.Response r = Http.attempt(api, null, null);
                        return r == null || r.status() != 200 ? null : messageTo(r.body(), email);
                    });
            assertThat(message.path("Subject").asString("")).as("subject of the mail in Mailpit").isNotBlank();
        }
    }

    /** The service that runs Mailpit. */
    static String mailpitService(ComposeModel compose) {
        List<String> found = compose.services().keySet().stream()
                .filter(s -> String.valueOf(compose.image(s)).startsWith("axllent/mailpit"))
                .toList();
        assertThat(found).as("services with the image axllent/mailpit in %s", compose.origin()).hasSize(1);
        return found.getFirst();
    }

    /** Mailpit's SMTP port: from {@code MP_SMTP_BIND_ADDR}, else Mailpit's default 1025. */
    private static int smtpPort(ComposeModel compose, String mailpit) {
        Object bind = compose.environment(mailpit).get("MP_SMTP_BIND_ADDR");
        if (bind == null) {
            return 1025;
        }
        String s = bind.toString();
        return Integer.parseInt(s.substring(s.lastIndexOf(':') + 1));
    }

    private static JsonNode messageTo(String body, String email) {
        for (JsonNode message : JSON.readTree(body).path("messages")) {
            for (JsonNode to : message.path("To")) {
                if (email.equalsIgnoreCase(to.path("Address").asString(""))) {
                    return message;
                }
            }
        }
        return null;
    }
}
