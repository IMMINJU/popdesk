package io.popdesk.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.popdesk.support.WebhookReceiver.Received;
import io.popdesk.support.WebhookReceiver.Reply;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Checks that the test support works: the app starts in a named setup, clients send their own Host
 * and keep their own cookies and CSRF token, the mail servers and the webhook receiver take
 * requests, and the clock, log, and database helpers are wired. It checks no app behavior, so it
 * passes before the slice is built.
 *
 * <p>The client checks run against {@link WebhookReceiver}, which records exactly what arrived.
 */
class SupportSelfTest extends ConnectedAppTest {

    @Autowired
    Clock appClock;

    @Test
    void appAnswersOnBothHosts() {
        assertThat(port).isPositive();
        assertThat(portal().get("/api/portal/me").send().status()).isBetween(100, 599);
        assertThat(console().get("/api/console/me").send().status()).isBetween(100, 599);
        assertThat(client("unknown.popdesk.test").get("/").send().status()).isBetween(100, 599);
    }

    @Test
    void clientSendsItsHostAndKeepsItsOwnCookies() {
        webhook().respond(r -> r.path().equals("/login")
                ? new Reply(204, Map.of("Set-Cookie", "SESSION=abc; Path=/; HttpOnly; SameSite=Lax"), "")
                : Reply.status(204));
        ApiClient portal = new ApiClient(AppTest.PORTAL_HOST, webhook().port());
        ApiClient console = new ApiClient(AppTest.CONSOLE_HOST, webhook().port());

        ApiResponse login = portal.get("/login").send();
        portal.get("/after").send();
        console.get("/other").send();
        portal.get("/odd").header("Host", "<svg onload=1>").send();

        assertThat(login.setCookie("SESSION")).contains("HttpOnly");
        assertThat(portal.cookie("SESSION")).contains("abc");
        assertThat(console.cookie("SESSION")).isEmpty();
        assertThat(received("/after").header("Host")).isEqualTo(AppTest.PORTAL_HOST);
        assertThat(received("/after").header("Cookie")).isEqualTo("SESSION=abc");
        assertThat(received("/other").header("Host")).isEqualTo(AppTest.CONSOLE_HOST);
        assertThat(received("/other").header("Cookie")).isNull();
        assertThat(received("/odd").header("Host")).isEqualTo("<svg onload=1>");
    }

    @Test
    void expiredCookieIsDropped() {
        webhook().respond(r -> new Reply(204, Map.of("Set-Cookie",
                r.path().equals("/set") ? "SESSION=abc; Path=/" : "SESSION=; Max-Age=0; Path=/"), ""));
        ApiClient portal = new ApiClient(AppTest.PORTAL_HOST, webhook().port());

        portal.get("/set").send();
        portal.get("/clear").send();

        assertThat(portal.cookie("SESSION")).isEmpty();
    }

    @Test
    void writesCarryTheCsrfTokenFromTheCsrfEndpoint() {
        webhook().respond(r -> r.path().equals(ApiClient.CSRF_PATH)
                ? new Reply(204, Map.of("Set-Cookie", ApiClient.CSRF_COOKIE + "=t1; Path=/; SameSite=Lax"), "")
                : Reply.status(201));
        ApiClient portal = new ApiClient(AppTest.PORTAL_HOST, webhook().port());

        portal.post("/write").json(Map.of("a", 1)).expect(201);
        portal.post("/bare").withoutCsrf().expect(201);
        portal.delete("/wrong").csrfToken("nope").expect(201);

        assertThat(received("/write").header(ApiClient.CSRF_HEADER)).isEqualTo("t1");
        assertThat(received("/write").header("Content-Type")).isEqualTo("application/json");
        assertThat(received("/write").text()).isEqualTo("{\"a\":1}");
        assertThat(received("/bare").header(ApiClient.CSRF_HEADER)).isNull();
        assertThat(received("/wrong").header(ApiClient.CSRF_HEADER)).isEqualTo("nope");
        assertThat(webhook().requests(r -> r.path().equals(ApiClient.CSRF_PATH))).hasSize(1);
    }

    @Test
    void csrfStepFailsAsAnAssertionWithoutTheCookie() {
        webhook().respondWith(501);
        ApiClient portal = new ApiClient(AppTest.PORTAL_HOST, webhook().port());

        assertThatThrownBy(() -> portal.post("/write").send())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Expected 204");
    }

    @Test
    void multipartRangeAndHeadArrive() {
        ApiClient console = new ApiClient(AppTest.CONSOLE_HOST, webhook().port());
        console.setCookie(ApiClient.CSRF_COOKIE, "t2");

        console.post("/upload")
                .jsonPart("issue", Map.of("title", "x"))
                .file("files", "a \"b\".txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8))
                .send();
        console.get("/file").header("Range", "bytes=0-9").send();
        console.head("/file").send();

        Received upload = received("/upload");
        assertThat(upload.header("Content-Type")).startsWith("multipart/form-data; boundary=");
        assertThat(upload.text())
                .contains("name=\"issue\"", "Content-Type: application/json", "{\"title\":\"x\"}")
                .contains("name=\"files\"; filename=\"a %22b%22.txt\"", "hello");
        assertThat(webhook().requests(r -> r.path().equals("/file")))
                .extracting(Received::method, r -> r.header("Range"))
                .containsExactly(
                        tuple("GET", "bytes=0-9"),
                        tuple("HEAD", null));
    }

    @Test
    void mailServersReceivePlainAndStartTlsMail() throws MessagingException {
        String plainTo = Unique.email("plain");
        String tlsTo = Unique.email("tls");
        int upgradedBefore = mail().startTlsConnections();

        send(mail().plainPort(), false, plainTo);
        send(mail().startTlsPort(), true, tlsTo);

        assertThat(mail().awaitMessageTo(plainTo).subject()).isEqualTo("Hello " + plainTo);
        assertThat(mail().awaitMessageTo(tlsTo).text()).contains("Body for " + tlsTo);
        assertThat(mail().startTlsConnections()).isGreaterThan(upgradedBefore);
    }

    @Test
    void startTlsServerRefusesPlainText() {
        int refusedBefore = mail().plainTextRefusals();

        assertThatThrownBy(() -> send(mail().startTlsPort(), false, Unique.email("refused")))
                .isInstanceOf(MessagingException.class);
        assertThat(mail().plainTextRefusals()).isGreaterThan(refusedBefore);
    }

    @Test
    void webhookReceiverAnswersWhatItIsTold() {
        ApiClient any = new ApiClient("hooks.example.test", webhook().port());
        webhook().respondWith(500);

        assertThat(any.post("/hook").withoutCsrf().jsonText("{\"text\":\"hi\"}").send().status()).isEqualTo(500);
        assertThat(webhook().await("hi", r -> r.text().contains("hi")).json().path("text").stringValue())
                .isEqualTo("hi");
        assertThat(webhook().url()).startsWith("http://127.0.0.1:");
    }

    @Test
    void clockIsTheAppsClockAndOnlyMovesForward() {
        Instant before = clock.instant();

        Instant after = clock.advance(Duration.ofHours(8));

        assertThat(appClock).isSameAs(clock);
        assertThat(after).isEqualTo(before.plus(Duration.ofHours(8)));
        assertThat(appClock.instant()).isEqualTo(after);
        assertThat(clock.withZone(ZoneId.of("Asia/Seoul")).instant()).isEqualTo(after);
        assertThatThrownBy(() -> clock.advance(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void logHoldsWhatTheJvmPrints() {
        AppLog.Mark mark = log.mark();
        String marker = "support-self-test " + Unique.number();

        System.out.println(marker);
        System.out.println("{\"message\":\"" + marker + "\"}");

        assertThat(log.all()).isNotEmpty();
        assertThat(log.since(mark)).contains(marker);
        assertThat(log.awaitLine(mark, marker)).contains(marker);
        assertThat(log.jsonLines(mark)).extracting(n -> n.path("message").stringValue()).contains(marker);
    }

    @Test
    void databaseCanBeRead() {
        assertThat(db.count("select 1")).isEqualTo(1);
        assertThat(db.row("select current_setting('server_version') as v").get("v").toString()).startsWith("16.");
        assertThat(db.tables()).isNotNull();
        assertThat(db.advisoryLocks()).isNotNull();
        assertThatThrownBy(() -> db.row("select 1 where false")).isInstanceOf(AssertionError.class);
    }

    @Test
    void waitEndsInAnAssertionError() {
        assertThatThrownBy(() -> Wait.until("nothing", Duration.ofMillis(300), () -> null))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> Wait.holds("false", Duration.ofMillis(300), () -> false))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void invitationTokenIsReadFromTheFragment() {
        String link = "http://portal.popdesk.test/invite#token=AbC-_09";

        assertThat(Fixtures.tokenOf(link)).isEqualTo("AbC-_09");
        assertThat(Fixtures.linkIn("Open <a href=\"" + link + "\">this</a>")).isEqualTo(link);
        assertThatThrownBy(() -> Fixtures.tokenOf("http://portal.popdesk.test/invite?token=x"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void fixtureStepsFailAsAssertions() {
        // Any status other than the expected one ends a fixture step with an AssertionError.
        ApiResponse response = portal().get("/api/portal/me").send();

        assertThatThrownBy(() -> response.expectStatus(response.status() + 1))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("/api/portal/me");
    }

    private Received received(String path) {
        return webhook().await(path, r -> r.path().equals(path));
    }

    private static void send(int port, boolean startTls, String to) throws MessagingException {
        Properties props = new Properties();
        props.put("mail.smtp.host", MailServer.HOST);
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", String.valueOf(startTls));
        props.put("mail.smtp.starttls.required", String.valueOf(startTls));
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout", "5000");
        Session session = Session.getInstance(props);
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(MailServer.FROM));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress(to));
        message.setSubject("Hello " + to);
        message.setText("Body for " + to, "UTF-8");
        Transport.send(message, MailServer.USER, MailServer.PASSWORD);
    }
}
