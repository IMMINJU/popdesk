package io.popdesk.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Predicate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * A real HTTP server inside the test JVM that stands in for a team webhook (Slack, Teams, and so
 * on). It keeps every request it gets and answers 204, or what a test tells it to answer.
 *
 * <p>One receiver for the whole JVM ({@link #shared()}), started on a free port on 127.0.0.1. Test
 * contexts that turn the webhook on point {@code NOTIFY_WEBHOOK_URL} at {@link #url()}. Requests from
 * earlier tests or other contexts can arrive at any time; find yours by a value only your test used
 * (an issue key, a title), not by count alone.
 */
public final class WebhookReceiver {

    /** What the receiver answers to one request. */
    public record Reply(int status, Map<String, String> headers, String body) {

        public static Reply status(int status) {
            return new Reply(status, Map.of(), "");
        }
    }

    /** One request the receiver got. */
    public record Received(String method, String path, Map<String, List<String>> headers, byte[] body,
            Instant receivedAt) {

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        /** The body as JSON. Fails with an AssertionError when it is not JSON. */
        public JsonNode json() {
            try {
                return ApiRequest.JSON.readTree(body);
            } catch (JacksonException e) {
                throw new AssertionError("Webhook body is not JSON: " + text(), e);
            }
        }

        /** First value of a header, names ignoring case, or null. */
        public String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .flatMap(e -> e.getValue().stream())
                    .findFirst()
                    .orElse(null);
        }
    }

    private static WebhookReceiver shared;

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile Function<Received, Reply> responder = r -> Reply.status(204);

    private WebhookReceiver() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "webhook-receiver");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", this::handle);
        server.start();
    }

    /** The receiver for this JVM, started on first use. */
    public static synchronized WebhookReceiver shared() {
        if (shared == null) {
            shared = new WebhookReceiver();
        }
        return shared;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** The address to set as {@code NOTIFY_WEBHOOK_URL}. */
    public String url() {
        return "http://127.0.0.1:" + port() + "/hook";
    }

    /** Every request so far, oldest first. */
    public List<Received> requests() {
        return List.copyOf(received);
    }

    public List<Received> requests(Predicate<Received> filter) {
        return received.stream().filter(filter).toList();
    }

    /** Waits for the first request that matches; fails with an AssertionError when none comes. */
    public Received await(String what, Predicate<Received> filter) {
        return Wait.until("a webhook request: " + what,
                () -> received.stream().filter(filter).findFirst().orElse(null));
    }

    /** Requests whose body contains {@code text}, for example an issue key. */
    public List<Received> containing(String text) {
        return requests(r -> r.text().contains(text));
    }

    /** Sets what the receiver answers from now on (for example 500 to make sending fail). */
    public void respond(Function<Received, Reply> responder) {
        this.responder = responder;
    }

    /** Answers {@code status} to every request from now on. */
    public void respondWith(int status) {
        respond(r -> Reply.status(status));
    }

    /** Forgets the requests and goes back to answering 204. */
    public void reset() {
        received.clear();
        responder = r -> Reply.status(204);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            body = in.readAllBytes();
        }
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, new ArrayList<>(v)));
        Received request = new Received(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                headers, body, Instant.now());
        received.add(request);

        Reply reply = responder.apply(request);
        reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
        boolean empty = out.length == 0 || exchange.getRequestMethod().equals("HEAD");
        exchange.sendResponseHeaders(reply.status(), empty ? -1 : out.length);
        if (!empty) {
            exchange.getResponseBody().write(out);
        }
        exchange.close();
    }
}
