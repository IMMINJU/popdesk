package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.Wait;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
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
 * The optional observability file, {@code deploy/compose.obs.yml}: one Grafana LGTM container next
 * to app and db, and the dashboards in {@code deploy/grafana/} (architecture.md section 6, ADR D-22).
 * Metric names are the ones the metrics tests use ({@code http_server_requests_seconds_count},
 * {@code popdesk_outbox_queued}, {@code popdesk_attachments_bytes}).
 */
@Tag("deploy")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ObservabilityComposeTest {

    static final String REQUESTS = "http_server_requests_seconds_count";
    static final String QUEUED = "popdesk_outbox_queued";
    static final String ATTACHMENT_BYTES = "popdesk_attachments_bytes";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Once<Stack> RUNNING = new Once<>("starting compose.yml with compose.obs.yml",
            ObservabilityComposeTest::start);

    @AfterAll
    static void stop() {
        Stack stack = RUNNING.peek();
        if (stack != null) {
            stack.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("S0-AC-79 the observability file adds exactly one service, Grafana LGTM at an exact version")
    void addsOneLgtmService() {
        ComposeModel release;
        ComposeModel both;
        try (Stack stack = Stack.of("obs", Repo.releaseCompose())) {
            release = ComposeModel.resolved(stack.config());
        }
        try (Stack stack = Stack.of("obs", Repo.releaseCompose(), Repo.obsCompose())) {
            both = ComposeModel.resolved(stack.config());
        }

        Set<String> added = new HashSet<>(both.services().keySet());
        added.removeAll(release.services().keySet());
        assertThat(added).as("services that compose.obs.yml adds").hasSize(1);
        String image = both.image(added.iterator().next());
        assertThat(image).as("image of the added service")
                .startsWith("grafana/otel-lgtm:")
                .doesNotContain(":latest")
                .matches("grafana/otel-lgtm:v?\\d+\\.\\d+\\.\\d+(@sha256:[0-9a-f]{64})?");
    }

    @Test
    @Order(2)
    @DisplayName("S0-AC-79 the dashboards in deploy/grafana query requests, the send queue and the attachment total")
    void dashboardsQueryTheMetrics() throws IOException {
        Path dir = Repo.path("deploy/grafana");
        assertThat(dir).as("deploy/grafana").isDirectory();
        List<String> queries = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                collectQueries(JSON.readTree(Files.readString(file, StandardCharsets.UTF_8)), queries);
            }
        }

        assertThat(queries).as("expr of the dashboard panels in %s", dir).isNotEmpty();
        for (String metric : List.of(REQUESTS, QUEUED, ATTACHMENT_BYTES)) {
            assertThat(queries).as("a dashboard query with %s", metric).anyMatch(q -> q.contains(metric));
        }
    }

    @Test
    @Order(3)
    @DisplayName("S0-AC-79 started with both files, one Grafana LGTM container runs next to app and db")
    void startsOneMoreContainer() {
        Stack stack = RUNNING.get();

        List<JsonNode> containers = stack.containers();
        List<String> running = containers.stream()
                .filter(c -> "running".equals(c.path("State").asString("")))
                .map(c -> c.path("Service").asString(""))
                .toList();
        assertThat(running).as("running services").contains("app", "db").hasSize(3);
        assertThat(containers).as("containers").anyMatch(c -> c.path("Image").asString("").startsWith("grafana/otel-lgtm"));
    }

    @Test
    @Order(4)
    @DisplayName("S0-AC-79 Grafana's Prometheus data source returns the request, send queue and attachment metrics of the app")
    void grafanaHasTheMetrics() {
        Stack stack = RUNNING.get();
        String lgtm = lgtmService(stack);
        stack.console().get("/api/console/me").send();
        String grafana = "http://127.0.0.1:" + stack.port(lgtm, 3000);
        String uid = Wait.until("Grafana's Prometheus data source", Duration.ofMinutes(2), () -> prometheusUid(grafana));

        for (String metric : List.of(REQUESTS, QUEUED, ATTACHMENT_BYTES)) {
            String url = grafana + "/api/datasources/proxy/uid/" + uid + "/api/v1/query?query="
                    + URLEncoder.encode(metric, StandardCharsets.UTF_8);
            JsonNode result = Wait.until(metric + " in Grafana", Duration.ofMinutes(3), () -> {
                Http.Response r = Http.attempt(url, "admin", "admin");
                if (r == null || r.status() != 200) {
                    return null;
                }
                JsonNode found = JSON.readTree(r.body()).path("data").path("result");
                return found.isArray() && !found.isEmpty() ? found : null;
            });
            assertThat(result.isEmpty()).as("series of %s", metric).isFalse();
        }
    }

    private static Stack start() {
        Stack stack = Stack.release("obs").with(Repo.obsCompose());
        try {
            stack.publish(lgtmService(stack), 3000);
            stack.up();
            stack.awaitHealthy("app");
            return stack;
        } catch (AssertionError | RuntimeException e) {
            stack.close();
            throw e;
        }
    }

    private static String lgtmService(Stack stack) {
        ComposeModel compose = ComposeModel.resolved(stack.config());
        List<String> found = compose.services().keySet().stream()
                .filter(s -> String.valueOf(compose.image(s)).startsWith("grafana/otel-lgtm"))
                .toList();
        assertThat(found).as("services with the image grafana/otel-lgtm").hasSize(1);
        return found.getFirst();
    }

    private static String prometheusUid(String grafana) {
        Http.Response r = Http.attempt(grafana + "/api/datasources", "admin", "admin");
        if (r == null || r.status() != 200) {
            return null;
        }
        for (JsonNode source : JSON.readTree(r.body())) {
            if ("prometheus".equals(source.path("type").asString(""))) {
                return source.path("uid").asString(null);
            }
        }
        return null;
    }

    /** Every {@code expr} string anywhere in a dashboard. */
    private static void collectQueries(JsonNode node, List<String> out) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                if (field.getKey().equals("expr") && field.getValue().isString()) {
                    out.add(field.getValue().stringValue());
                } else {
                    collectQueries(field.getValue(), out);
                }
            }
        } else if (node.isArray()) {
            node.forEach(child -> collectQueries(child, out));
        }
    }
}
