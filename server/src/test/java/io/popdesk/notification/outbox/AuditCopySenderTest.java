package io.popdesk.notification.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.popdesk.notification.outbox.OutboxRepository.OutboxRow;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The sender of the audit log's outside copy against a receiver on this machine, for what the API
 * tests have no context for: a copy without a token, and the rows that are dropped.
 */
class AuditCopySenderTest {

    private static final long ENTRY = 41;

    private static final String BODY = "{\"id\":41,\"action\":\"COMPANY_CREATED\"}";

    /** One request as the receiver got it. */
    private record Got(String method, String path, Map<String, List<String>> headers, String body) {}

    private final List<Got> received = new CopyOnWriteArrayList<>();
    private HttpServer receiver;
    private int answer = 204;

    @BeforeEach
    void startReceiver() throws IOException {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/", exchange -> {
            received.add(new Got(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    Map.copyOf(exchange.getRequestHeaders()),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            if (answer == 302) {
                exchange.getResponseHeaders().add("Location", url("/moved"));
            }
            exchange.sendResponseHeaders(answer, -1);
            exchange.close();
        });
        receiver.start();
    }

    @AfterEach
    void stopReceiver() {
        receiver.stop(0);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + receiver.getAddress().getPort() + path;
    }

    private MockEnvironment copyOn() {
        return new MockEnvironment().withProperty("AUDIT_FORWARD_URL", url("/copy"));
    }

    private static AuditCopySender sender(MockEnvironment environment) {
        return new AuditCopySender(id -> id == ENTRY ? Optional.of(BODY) : Optional.empty(), environment);
    }

    private static OutboxRow row(Long auditId) {
        return new OutboxRow(1, OutboxRepository.AUDIT_FORWARD, "AUDIT_ENTRY", null, null, null, auditId, 0, null,
                Instant.parse("2026-10-12T00:00:00Z"));
    }

    @Test
    @DisplayName("S1B-AC-32 without AUDIT_FORWARD_TOKEN the copy is posted with no Authorization header")
    void noTokenNoAuthorizationHeader() {
        Attempt attempt = sender(copyOn()).attempt(row(ENTRY));

        assertThat(attempt.outcome()).isEqualTo(Attempt.Outcome.SENT);
        assertThat(received).hasSize(1);
        Got got = received.getFirst();
        assertThat(got.method()).isEqualTo("POST");
        assertThat(got.path()).isEqualTo("/copy");
        assertThat(got.body()).isEqualTo(BODY);
        assertThat(got.headers().get("Content-type")).containsExactly("application/json");
        assertThat(got.headers().keySet()).noneMatch("authorization"::equalsIgnoreCase);
    }

    @Test
    @DisplayName("S1B-AC-32 an AUDIT_FORWARD_TOKEN of spaces only is no token: no Authorization header")
    void blankTokenNoAuthorizationHeader() {
        sender(copyOn().withProperty("AUDIT_FORWARD_TOKEN", "  ")).attempt(row(ENTRY));

        assertThat(received).hasSize(1);
        assertThat(received.getFirst().headers().keySet()).noneMatch("authorization"::equalsIgnoreCase);
    }

    @Test
    @DisplayName("S1B-AC-32 with AUDIT_FORWARD_TOKEN the copy carries Authorization: Bearer and the token")
    void tokenIsSentAsBearer() {
        sender(copyOn().withProperty("AUDIT_FORWARD_TOKEN", "unit-Token-1")).attempt(row(ENTRY));

        assertThat(received).hasSize(1);
        assertThat(received.getFirst().headers().get("Authorization")).containsExactly("Bearer unit-Token-1");
    }

    @Test
    @DisplayName("S1B-AC-30 a 302 is a failure named by its status only, and the address it names gets nothing")
    void redirectIsAFailure() {
        answer = 302;

        Attempt attempt = sender(copyOn().withProperty("AUDIT_FORWARD_TOKEN", "unit-Token-1")).attempt(row(ENTRY));

        assertThat(attempt.outcome()).isEqualTo(Attempt.Outcome.FAILED);
        assertThat(attempt.errorKind()).isEqualTo("HTTP_302");
        assertThat(received.stream().map(Got::path).toList()).containsExactly("/copy");
    }

    @Test
    @DisplayName("a copy whose audit entry is gone is dropped without a request")
    void entryGoneIsDropped() {
        Attempt attempt = sender(copyOn()).attempt(row(ENTRY + 1));

        assertThat(attempt.outcome()).isEqualTo(Attempt.Outcome.DROPPED);
        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("S1B-AC-31 a copy row met in a sandbox, or with the address taken away, is dropped without a "
            + "request")
    void rowMetWhileTheCopyIsOffIsDropped() {
        Attempt sandbox = sender(copyOn().withProperty("POPDESK_SANDBOX", "true")).attempt(row(ENTRY));
        Attempt noAddress = sender(new MockEnvironment()).attempt(row(ENTRY));

        assertThat(sandbox.outcome()).isEqualTo(Attempt.Outcome.DROPPED);
        assertThat(noAddress.outcome()).isEqualTo(Attempt.Outcome.DROPPED);
        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("S1B-AC-32 an address that is no URL, or a token no header can carry, fails with a kind that "
            + "holds neither")
    void badAddressOrTokenFailsWithAKindOnly() {
        Attempt badAddress = sender(new MockEnvironment().withProperty("AUDIT_FORWARD_URL", "not a url"))
                .attempt(row(ENTRY));
        Attempt badToken = sender(copyOn().withProperty("AUDIT_FORWARD_TOKEN", "line\nbreak")).attempt(row(ENTRY));

        assertThat(badAddress.errorKind()).isEqualTo("AUDIT_FORWARD_URL_INVALID");
        assertThat(badToken.errorKind()).isEqualTo("AUDIT_FORWARD_TOKEN_INVALID");
        assertThat(received).isEmpty();
    }
}
