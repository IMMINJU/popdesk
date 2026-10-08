package io.popdesk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the contract files docs/integrate/openapi-{portal,console}.json from the running app
 * (ADR D-21, D-43). Run with {@code ./mvnw -Popenapi test}; a normal test run leaves this out.
 *
 * <p>The output is the same on every run and every OS: object keys sorted, two-space indent, LF line
 * ends, one line break at the end, and no {@code servers} (it holds the random test port).
 */
@Tag("openapi")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "springdoc.api-docs.enabled=true")
@Import(TestcontainersConfiguration.class)
class OpenApiFilesTest {

    static final JsonMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    @LocalServerPort
    int port;

    @ParameterizedTest
    @ValueSource(strings = {"portal", "console"})
    void writesContractFile(String group) throws IOException, InterruptedException {
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newHttpClient()) {
            response = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs/" + group)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        assertThat(response.statusCode()).isEqualTo(200);

        @SuppressWarnings("unchecked")
        Map<String, Object> document = JSON.readValue(response.body(), Map.class);
        document.remove("servers");
        assertThat(document).containsKeys("openapi", "info", "paths");

        String text = JSON.writeValueAsString(document).replace("\r\n", "\n") + "\n";
        Path file = outputDir().resolve("openapi-" + group + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    static Path outputDir() {
        String dir = System.getProperty("popdesk.openapi.dir");
        return dir != null ? Path.of(dir) : Path.of("..", "docs", "integrate");
    }
}
