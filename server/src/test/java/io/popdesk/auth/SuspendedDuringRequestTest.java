package io.popdesk.auth;

import static io.popdesk.auth.AuthChecks.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An account suspended while one of its requests is still running. The app writes a session back at
 * the end of each request; a session that the suspension deleted meanwhile must stay deleted.
 *
 * <p>The running request is a comment whose body is only half sent, on a socket of the test's own:
 * the app has read the headers and the session and waits for the rest of the body. The attachment
 * tests have a class for held requests ({@code attachment.HeldRequest}); it is not visible from this
 * package (report: candidate for support).
 */
class SuspendedDuringRequestTest extends BareAppTest {

    private static final Pattern STATUS = Pattern.compile("^HTTP/1\\.1 (\\d{3})");

    @Test
    @DisplayName("S1A-AC-86 a request that was running when its account was suspended does not bring the "
            + "session back: the cookie gets 401, also after the account is active again")
    void runningRequestDoesNotBringTheSessionBack() throws IOException {
        Session admin = fixtures.signInAdmin();
        String code = fixtures.createCompany(admin).code();
        Session customer = fixtures.customer(admin, code);
        Issue issue = fixtures.fileIssue(customer, code);
        byte[] body = "{\"content\":\"Sent while the account was being suspended\"}".getBytes(StandardCharsets.UTF_8);
        int status;

        try (Socket socket = startRequest(customer.client(), "/api/portal/issues/" + issue.key() + "/comments", body,
                body.length / 2)) {
            // A round trip of another request, so the held one has passed the sign-in check before the suspension.
            admin.client().get("/api/console/me").expect(200);
            admin.client().post("/api/console/accounts/" + customer.accountId() + "/suspend").expect(200);
            status = finishRequest(socket, body, body.length / 2);
        }

        assertThat(status).as("status of the request that was running; 401 means it had not started").isNotEqualTo(401);
        assertError(customer.client().get("/api/portal/me").send(), 401, "UNAUTHENTICATED");
        admin.client().post("/api/console/accounts/" + customer.accountId() + "/reactivate").expect(200);
        assertError(customer.client().get("/api/portal/me").send(), 401, "UNAUTHENTICATED");
        assertError(customer.client().get("/api/portal/issues/" + issue.key()).send(), 401, "UNAUTHENTICATED");
    }

    /** Sends the headers of a JSON POST and the first {@code sent} bytes of its body, and stops there. */
    private static Socket startRequest(ApiClient client, String path, byte[] body, int sent) {
        StringBuilder cookies = new StringBuilder();
        client.cookies().forEach((name, value) -> cookies.append(cookies.isEmpty() ? "" : "; ").append(name)
                .append('=').append(value));
        String head = "POST " + path + " HTTP/1.1\r\n"
                + "Host: " + client.host() + "\r\n"
                + ApiClient.FORWARDED_FOR + ": " + client.forwardedFor() + "\r\n"
                + "Cookie: " + cookies + "\r\n"
                + ApiClient.CSRF_HEADER + ": " + client.csrfToken() + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        try {
            Socket socket = new Socket();
            socket.setSoTimeout(60_000);
            socket.connect(new InetSocketAddress("127.0.0.1", client.port()), 10_000);
            socket.getOutputStream().write(head.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().write(body, 0, sent);
            socket.getOutputStream().flush();
            return socket;
        } catch (IOException e) {
            throw new AssertionError("Could not start the held request: " + e, e);
        }
    }

    /** Sends the rest of the body and returns the status of the response. */
    private static int finishRequest(Socket socket, byte[] body, int sent) {
        try {
            socket.getOutputStream().write(body, sent, body.length - sent);
            socket.getOutputStream().flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            for (int b = in.read(); b != -1 && b != '\n'; b = in.read()) {
                line.write(b);
            }
            Matcher m = STATUS.matcher(line.toString(StandardCharsets.ISO_8859_1));
            if (!m.find()) {
                throw new AssertionError("The held request got no HTTP status line: '" + line + "'");
            }
            return Integer.parseInt(m.group(1));
        } catch (IOException e) {
            throw new AssertionError("The held request failed while finishing: " + e, e);
        }
    }
}
