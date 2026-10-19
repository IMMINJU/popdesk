package io.popdesk.support;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The OIDC provider of the tests (slice 11, D-66): mock-oauth2-server in a container, one per JVM, started the first
 * time a test asks for it. It is a real provider: a discovery document, a JWKS, and ID tokens signed with RS256.
 *
 * <p>Its interactive login stands in for the person at the provider: {@link #logIn} posts {@code username} (the
 * token's {@code sub}) and {@code claims} (a JSON object put into the ID token over the provider's own claims, so a
 * test also sets {@code iss}, {@code aud}, {@code azp}, {@code nonce}, {@code iat}, {@code exp}; a claim with the
 * value {@code null} is left out) to the authorization endpoint the app sent the browser to, and gets the redirect to
 * the app with {@code code} and {@code state}. The provider does not check the client secret.
 *
 * <p>The issuer is {@code http://localhost:{port}/default}; another path ({@code /other}) is another issuer with keys
 * of its own. The app and the tests reach it at the same address, so the {@code iss} it writes is the one configured.
 */
public final class OidcProvider {

    public static final DockerImageName IMAGE = DockerImageName.parse("ghcr.io/navikt/mock-oauth2-server:2.1.10");

    /** The client ID the OIDC contexts give the app ({@code OIDC_CLIENT_ID}). */
    public static final String CLIENT_ID = "popdesk-test-client";

    /** The client secret the OIDC contexts give the app ({@code OIDC_CLIENT_SECRET}); never in a log. */
    public static final String CLIENT_SECRET = "rst-oidc-client-secret-5b0e7c";

    /** The issuer path the app is configured with. */
    public static final String DEFAULT = "default";

    private static final int PORT = 8080;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static OidcProvider shared;

    private final GenericContainer<?> container;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private OidcProvider() {
        container = new GenericContainer<>(IMAGE)
                .withEnv("JSON_CONFIG", "{\"interactiveLogin\":true}")
                .withExposedPorts(PORT)
                .waitingFor(Wait.forHttp("/" + DEFAULT + "/.well-known/openid-configuration").forPort(PORT)
                        .withStartupTimeout(Duration.ofSeconds(90)));
        container.start();
    }

    /** The provider of this JVM; starts its container on the first call. */
    public static synchronized OidcProvider shared() {
        if (shared == null) {
            shared = new OidcProvider();
        }
        return shared;
    }

    /** {@code http://localhost:{port}/default}: the value of {@code OIDC_ISSUER}. */
    public String issuer() {
        return issuer(DEFAULT);
    }

    /** The issuer at another path of the same provider, {@code http://localhost:{port}/{path}}. */
    public String issuer(String path) {
        return "http://localhost:" + container.getMappedPort(PORT) + "/" + path;
    }

    /** {@code http://localhost:{port}}: where the provider is, for a test that compares addresses. */
    public String origin() {
        return "http://localhost:" + container.getMappedPort(PORT);
    }

    /**
     * Logs in at the provider: posts {@code username} = {@code sub} and {@code claims} to the authorization address
     * the app sent the browser to, and returns the redirect the provider answers. Fails with an AssertionError when
     * the provider does not answer 302.
     */
    public Redirect logIn(String authorizationUrl, String sub, Map<String, Object> claims) {
        String form = "username=" + URLEncoder.encode(sub, StandardCharsets.UTF_8)
                + "&claims=" + URLEncoder.encode(JSON.writeValueAsString(claims), StandardCharsets.UTF_8);
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(authorizationUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)));
        String location = response.headers().firstValue("Location").orElse(null);
        if (response.statusCode() != 302 || location == null) {
            throw new AssertionError("The provider answered " + response.statusCode() + " to the login at "
                    + authorizationUrl + ": " + response.body());
        }
        return new Redirect(URI.create(location), query(URI.create(location)));
    }

    /**
     * Whether no client has exchanged {@code code} at the token endpoint yet. Asks the endpoint with a PKCE verifier
     * that cannot match: the provider refuses that with 400 while it still holds the code, and answers 200 with a
     * token of an unknown person once the code was used (it then has no challenge to compare with).
     */
    public boolean codeUnused(String code) {
        String basic = Base64.getEncoder().encodeToString((CLIENT_ID + ":" + CLIENT_SECRET)
                .getBytes(StandardCharsets.UTF_8));
        String form = "grant_type=authorization_code&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                + "&redirect_uri=" + URLEncoder.encode("http://unused.popdesk.test/", StandardCharsets.UTF_8)
                + "&code_verifier=" + "never-the-verifier-".repeat(3);
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(issuer() + "/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", "Basic " + basic)
                .POST(HttpRequest.BodyPublishers.ofString(form)));
        if (response.statusCode() == 400) {
            return true;
        }
        if (response.statusCode() == 200) {
            return false;
        }
        throw new AssertionError("The token endpoint answered " + response.statusCode() + ": " + response.body());
    }

    /** The provider's discovery document. */
    public JsonNode discovery() {
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                URI.create(issuer() + "/.well-known/openid-configuration")).GET());
        if (response.statusCode() != 200) {
            throw new AssertionError("The discovery document answered " + response.statusCode());
        }
        return JSON.readTree(response.body());
    }

    /** The query parameters of an address, decoded; a name given twice keeps its last value. */
    public static Map<String, String> query(URI uri) {
        Map<String, String> out = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(URLDecoder.decode(name, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return out;
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return http.send(request.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new AssertionError("The OIDC provider gave no answer: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while calling the OIDC provider", e);
        }
    }

    /** Where the provider sent the browser after the login: the app's callback with its query. */
    public record Redirect(URI location, Map<String, String> query) {

        public String code() {
            return query.get("code");
        }

        public String state() {
            return query.get("state");
        }
    }
}
