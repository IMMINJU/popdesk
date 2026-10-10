package io.popdesk.issue;

import static io.popdesk.issue.IssueSteps.items;
import static io.popdesk.issue.IssueSteps.text;
import static io.popdesk.issue.IssueSteps.texts;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueSteps.Scene;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The size limit of the JSON body of {@code POST /api/portal/issues} (verify.md V-4): 256 KB, counted
 * as 2^10 bytes like the other sizes (tasks.md 6절). A body over it is 413 {@code PAYLOAD_TOO_LARGE}
 * and files nothing; the largest body the field limits allow (a title of 200 and a content of 10,000
 * characters) fits with room to spare.
 *
 * <p>Bodies are brought to an exact size with spaces between the JSON members, which mean nothing to
 * a JSON parser.
 */
class IssueBodyLimitTest extends BareAppTest {

    private static final int LIMIT = 256 * 1024;
    private static final String PATH = "/api/portal/issues";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private IssueSteps steps;

    @BeforeEach
    void setUp() {
        steps = new IssueSteps(fixtures, clock);
    }

    @Test
    @DisplayName("V-4 a JSON body of exactly 256 KB with the longest title and content is filed")
    void bodyAtTheLimitIsFiled() {
        Scene s = steps.scene();
        String title = longestTitle();
        String content = "c".repeat(10_000);

        ApiResponse created = s.customer().client().post(PATH)
                .body(body(s.code(), title, content, LIMIT), "application/json").send();

        created.expectStatus(201);
        JsonNode read = s.customer().client().get(PATH + "/" + text(created.json(), "key")).expect(200).json();
        assertThat(text(read, "title")).isEqualTo(title);
        assertThat(text(read, "content")).isEqualTo(content);
    }

    @Test
    @DisplayName("V-4 a JSON body one byte over 256 KB is 413 PAYLOAD_TOO_LARGE and files no issue")
    void bodyOneByteOverIsRefused() {
        Scene s = steps.scene();
        String title = longestTitle();
        long before = issueCount(s.customer());

        ApiResponse refused = s.customer().client().post(PATH)
                .body(body(s.code(), title, "c".repeat(10_000), LIMIT + 1), "application/json").send();

        assertTooLarge(refused);
        assertNothingFiled(s.customer(), title, before);
    }

    @Test
    @DisplayName("V-4 a JSON body of 1 MB is 413 PAYLOAD_TOO_LARGE, not a 400 about its fields")
    void largeBodyIsRefusedBeforeItsFieldsAreChecked() {
        Scene s = steps.scene();
        String title = longestTitle();
        long before = issueCount(s.customer());

        ApiResponse refused = s.customer().client().post(PATH)
                .body(body(s.code(), title, "c".repeat(1024 * 1024), 0), "application/json").send();

        assertTooLarge(refused);
        assertNothingFiled(s.customer(), title, before);
    }

    @Test
    @DisplayName("V-4 a JSON body over 256 KB sent without Content-Length (chunked) is 413 PAYLOAD_TOO_LARGE and files no issue")
    void chunkedBodyOverTheLimitIsRefused() {
        Scene s = steps.scene();
        String title = longestTitle();
        long before = issueCount(s.customer());

        Chunked refused = postChunked(s.customer().client(), body(s.code(), title, "c".repeat(10_000), LIMIT + 1));

        assertThat(refused.status()).as("status of a chunked body of %d bytes: %s", LIMIT + 1, refused.body())
                .isEqualTo(413);
        assertThat(refused.code()).as("code in %s", refused.body()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertNothingFiled(s.customer(), title, before);
    }

    private static void assertTooLarge(ApiResponse response) {
        assertThat(response.status()).as(response + " " + response.snippet()).isEqualTo(413);
        assertThat(response.errorCode()).as(response + " " + response.snippet()).isEqualTo("PAYLOAD_TOO_LARGE");
    }

    private static void assertNothingFiled(Session customer, String title, long before) {
        ApiResponse list = customer.client().get(PATH).query("size", 100).expect(200);
        assertThat(texts(items(list), "title")).as("titles the customer sees").doesNotContain(title);
        assertThat(list.json().path("totalElements").asLong()).as("issues the customer sees").isEqualTo(before);
    }

    private static long issueCount(Session customer) {
        return customer.client().get(PATH).expect(200).json().path("totalElements").asLong();
    }

    /** A title of 200 characters that no other test uses. */
    private static String longestTitle() {
        String start = Unique.text("Body limit") + " ";
        return start + "t".repeat(200 - start.length());
    }

    /**
     * A filing as JSON of exactly {@code bytes} bytes, padded with spaces after the opening brace;
     * with {@code bytes} 0, as long as its fields make it.
     */
    private static byte[] body(String code, String title, String content, int bytes) {
        String members = "\"companyCode\":" + JSON.writeValueAsString(code) + ",\"title\":" + JSON.writeValueAsString(title)
                + ",\"content\":" + JSON.writeValueAsString(content) + "}";
        int padding = bytes == 0 ? 0 : bytes - 1 - members.getBytes(StandardCharsets.UTF_8).length;
        if (padding < 0) {
            throw new AssertionError("The fields alone are longer than " + bytes + " bytes");
        }
        return ("{" + " ".repeat(padding) + members).getBytes(StandardCharsets.UTF_8);
    }

    /** What a chunked request got. */
    private record Chunked(int status, String body) {

        String code() {
            try {
                JsonNode code = JSON.readTree(body).path("code");
                return code.isString() ? code.stringValue() : null;
            } catch (RuntimeException notJson) {
                return null;
            }
        }
    }

    /**
     * The same request as {@code client.post(PATH).body(...)}, but with the body as a stream of unknown
     * length, so it goes out with {@code Transfer-Encoding: chunked} and no {@code Content-Length}.
     * {@code ApiRequest} always sends the length; a limit that reads only that header would miss this.
     */
    private static Chunked postChunked(ApiClient client, byte[] body) {
        String cookies = client.cookies().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("; "));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + client.port() + PATH))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(60))
                .header("Host", client.host())
                .header(ApiClient.FORWARDED_FOR, client.forwardedFor())
                .header(ApiClient.CSRF_HEADER, client.csrfToken())
                .header("Cookie", cookies)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
                .build();
        try (HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Chunked(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new AssertionError("The chunked POST " + PATH + " got no response: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("The chunked POST " + PATH + " was interrupted", e);
        }
    }
}
