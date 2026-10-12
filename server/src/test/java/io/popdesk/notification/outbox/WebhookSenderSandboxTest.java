package io.popdesk.notification.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.popdesk.notification.IssueFacts.Issue;
import io.popdesk.notification.Sandbox;
import io.popdesk.notification.outbox.OutboxRepository.OutboxRow;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/**
 * The webhook sender in a sandbox, against a receiver on this machine: a webhook row that was in the
 * queue when the sandbox started (it came with the copied database) is dropped and not posted. A
 * sandbox queues no such row itself, so no API test can have one.
 */
class WebhookSenderSandboxTest {

    private final List<String> received = new CopyOnWriteArrayList<>();
    private final List<UUID> asked = new CopyOnWriteArrayList<>();
    private HttpServer receiver;

    @BeforeEach
    void startReceiver() throws IOException {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/", exchange -> {
            received.add(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        receiver.start();
    }

    @AfterEach
    void stopReceiver() {
        receiver.stop(0);
    }

    @ParameterizedTest(name = "POPDESK_SANDBOX={0}")
    @ValueSource(strings = {"true", " TRUE "})
    @DisplayName("S4B-AC-04 in a sandbox a webhook row that waits in the queue is dropped: nothing is posted to "
            + "NOTIFY_WEBHOOK_URL although it is set, and the issue is not even read")
    void queuedRowIsDroppedNotPosted(String sandbox) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("NOTIFY_WEBHOOK_URL", "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook")
                .withProperty("POPDESK_SANDBOX", sandbox);
        // The address and the language are for the message's text, which a sandbox never makes.
        WebhookSender sender = new WebhookSender(id -> {
            asked.add(id);
            return Optional.of(new Issue("ACME-1", "Printer", "ACME"));
        }, null, environment, null, Sandbox.of(environment::getProperty));
        OutboxRow row = new OutboxRow(1, OutboxRepository.WEBHOOK, "ISSUE_CREATED", null, UUID.randomUUID(), null,
                null, 0, null, Instant.parse("2026-10-12T00:00:00Z"));

        Attempt attempt = sender.attempt(row);

        assertThat(attempt.outcome()).isEqualTo(Attempt.Outcome.DROPPED);
        assertThat(received).as("requests the receiver got").isEmpty();
        assertThat(asked).as("issues read for the message").isEmpty();
    }
}
