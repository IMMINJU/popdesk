package io.popdesk.start;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.mailin.Inbound;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.App;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailInServer;
import io.popdesk.support.MailInServer.Folder;
import io.popdesk.support.MailServer;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Starting with mail-in set up (S12-AC-02): the app does not reach for the IMAP server while it starts, so it starts
 * with the server down and takes requests, warns about plain text, and its first round fails with {@code CONNECT}. In a
 * sandbox (S12-AC-03) mail-in is off although set up: no round reads the mailbox, the status says why, the start warns,
 * and the mail to a customer has no {@code Reply-To}.
 *
 * <p>Whether {@code IMAP_PORT} defaults to 993 is not seen here: the status shows no port, and a closed port 993 fails
 * the same as any closed port.
 */
class MailInStartTest extends OwnStartTest {

    private static final String PASSWORD = "rst-imap-down-secret-6c41f9";

    @Test
    @DisplayName("S12-AC-02 with mail-in set up and the IMAP server down the app starts, is ready, the admin signs in, a customer files an issue, IMAP_ALLOW_PLAINTEXT gets a WARN line, mail-in is on and its round fails with CONNECT, and the log never holds the password")
    void startsWithTheServerDown() {
        Settings settings = Settings.firstInstall(postgres.newDatabase())
                .set("IMAP_HOST", "127.0.0.1")
                .set("IMAP_PORT", String.valueOf(closedPort()))
                .set("IMAP_USER", "start-support")
                .set("IMAP_PASSWORD", PASSWORD)
                .set("IMAP_ALLOW_PLAINTEXT", "true")
                .set("MAIL_INBOUND_ADDRESS", "support@mail.example");
        try (Boot app = Boot.start(settings)) {
            app.assertStarted();
            assertReady(app);
            StorageSteps.Scene scene = StorageSteps.scene(app);

            String key = StorageSteps.fileIssue(scene.customer(), scene.code());

            assertThat(key).as("key of the issue filed while the IMAP server is down").startsWith(scene.code() + "-");
            assertThat(app.logLines()).as("a WARN line at startup about IMAP_ALLOW_PLAINTEXT")
                    .anyMatch(line -> line.contains("WARN") && line.contains("IMAP_ALLOW_PLAINTEXT"));
            JsonNode mailIn = Wait.until("a failed mail-in round", () -> {
                JsonNode now = status(scene.admin()).path("mailIn");
                return now.path("roundFailures").asInt(0) > 0 ? now : null;
            });
            assertThat(mailIn.path("enabled").asBoolean(false)).as("mailIn.enabled in %s", mailIn).isTrue();
            assertThat(mailIn.path("lastFailure").path("kind").asString(null)).as("lastFailure.kind in %s", mailIn)
                    .isEqualTo("CONNECT");
            assertThat(app.log()).as("the log of the start").doesNotContain(PASSWORD);
        }
    }

    @Test
    @DisplayName("S12-AC-03 in a sandbox with mail-in set up no round reads the mailbox, mailIn is off with offReason SANDBOX, a WARN line at startup says mail-in is off without an address, and a customer's issue mail has no Reply-To")
    void sandboxTurnsMailInOff() {
        MailInServer server = MailInServer.shared();
        String sandboxTo = Unique.email("sandbox");
        Inbound waiting = Inbound.mail().from(Unique.email("nobody")).text("Waiting " + Unique.number());
        server.deliver(waiting.bytes());
        Settings settings = Settings.firstInstall(postgres.newDatabase())
                .set("POPDESK_SANDBOX", "true")
                .set("POPDESK_SANDBOX_MAIL_TO", sandboxTo)
                .set("SMTP_HOST", MailInServer.HOST)
                .set("SMTP_PORT", String.valueOf(server.smtpPort()))
                .set("SMTP_USER", MailServer.USER)
                .set("SMTP_PASSWORD", MailServer.PASSWORD)
                .set("SMTP_ALLOW_PLAINTEXT", "true")
                .set("MAIL_FROM", MailServer.FROM)
                .set("IMAP_HOST", MailInServer.HOST)
                .set("IMAP_PORT", String.valueOf(server.imapPort()))
                .set("IMAP_USER", MailInServer.IMAP_USER)
                .set("IMAP_PASSWORD", MailInServer.IMAP_PASSWORD)
                .set("IMAP_FOLDER", Folder.INBOUND.folderName())
                .set("IMAP_PROCESSED_FOLDER", Folder.PROCESSED.folderName())
                .set("IMAP_QUARANTINE_FOLDER", Folder.QUARANTINE.folderName())
                .set("IMAP_ALLOW_PLAINTEXT", "true")
                .set("MAIL_INBOUND_ADDRESS", MailInServer.ADDRESS);
        try (Boot app = Boot.start(settings)) {
            app.assertStarted();
            assertReady(app);
            Session admin = StorageSteps.signIn(app, App.CONSOLE, Settings.ADMIN_EMAIL, Settings.ADMIN_PASSWORD);

            JsonNode mailIn = status(admin).path("mailIn");

            assertThat(mailIn.path("enabled").isBoolean() && !mailIn.path("enabled").booleanValue())
                    .as("mailIn.enabled is false in %s", mailIn).isTrue();
            assertThat(mailIn.path("offReason").asString(null)).as("mailIn.offReason").isEqualTo("SANDBOX");
            List<String> warnings = app.logLines().stream().filter(line -> line.contains("WARN") && mailIn(line))
                    .toList();
            assertThat(warnings).as("WARN lines at startup about mail-in").isNotEmpty();
            assertThat(warnings).as("WARN lines about mail-in").noneMatch(line -> line.contains(MailInServer.ADDRESS));
            Wait.holds("the waiting mail stays unread in the incoming folder", Duration.ofSeconds(5), () ->
                    server.withMessageId(Folder.INBOUND, waiting.messageId()).stream().anyMatch(m -> !m.seen()));

            ReceivedMail toCustomer = customerIssueMail(app, admin, server, sandboxTo);

            assertThat(toCustomer.header("Reply-To")).as("Reply-To of the customer's issue mail").isEmpty();
        }
    }

    /** A customer files an issue, the admin answers it, and the mail about it comes to the sandbox address. */
    private static ReceivedMail customerIssueMail(Boot app, Session admin, MailInServer server, String sandboxTo) {
        String code = Unique.companyCode();
        admin.client().post("/api/console/companies")
                .json(Map.of("code", code, "name", "Company " + code, "legalName", "Company " + code + " Ltd."))
                .expect(201);
        String email = Unique.email("customer");
        admin.client().post("/api/console/invitations")
                .json(Map.of("email", email, "name", Unique.text("customer"), "role", "CUSTOMER", "companyCodes",
                        List.of(code)))
                .expect(201);
        ReceivedMail invitation = server.awaitSent("the invitation at the sandbox address",
                m -> m.isTo(sandboxTo) && m.text().contains("/invite#token="));
        app.portal().post("/api/public/invitations/accept")
                .json(Map.of("token", Fixtures.tokenOf(Fixtures.linkIn(invitation.text())), "password",
                        Fixtures.PASSWORD))
                .expect(204);
        Session customer = StorageSteps.signIn(app, App.PORTAL, email, Fixtures.PASSWORD);
        String key = StorageSteps.fileIssue(customer, code);
        StorageSteps.comment(admin, key);
        return server.awaitSent("the mail about " + key + " at the sandbox address",
                m -> m.isTo(sandboxTo) && m.subject() != null && m.subject().contains("[" + key + "]"));
    }

    /** A line about mail-in being off: it names mail-in (IMAP, incoming or receiving email) and says off or sandbox. */
    private static boolean mailIn(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        boolean mailIn = lower.contains("imap") || lower.contains("incoming") || lower.contains("mail-in")
                || lower.contains("receiving");
        return mailIn && (lower.contains("off") || lower.contains("sandbox"));
    }

    private static JsonNode status(Session admin) {
        ApiResponse status = admin.client().get("/api/console/system/status").expect(200);
        return status.json();
    }

    /** A local port that was free a moment ago and has nothing listening on it. */
    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
