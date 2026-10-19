package io.popdesk.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.PlainHeader;
import com.nimbusds.jose.PlainObject;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import tools.jackson.databind.json.JsonMapper;

/**
 * An OIDC provider inside the test JVM whose ID tokens the test writes (slice 11, acceptance.md 5절 17): a JDK
 * {@link HttpServer} on 127.0.0.1 with a discovery document, a JWKS, and a token endpoint. It has no authorization
 * endpoint that does anything: the test reads {@code state} and {@code nonce} from the start's 302, makes up a code,
 * gives that code an ID token ({@link #give}), and opens the app's callback with the code; the app's token request
 * for that code gets the token.
 *
 * <p>The test signs the token as it likes: with the RS256 or the ES256 key in the JWKS ({@link #rs256},
 * {@link #es256}), unsigned ({@code alg: none}, {@link #unsigned}), HS256 with the client secret ({@link #hs256}), or
 * with any key ({@link #signed}), for example one not in the JWKS yet ({@link #publish} adds it). The claims are a map
 * the test fills, so {@code iss}, {@code aud}, {@code azp}, {@code nonce}, {@code exp}, {@code iat} are what it says.
 *
 * <p>Switches: the discovery document or the token endpoint answers another status ({@link #discoveryAnswers},
 * {@link #tokenAnswers}); {@link #reset} goes back to normal. The provider counts the requests it got per endpoint
 * ({@link #requests}). One provider for the whole JVM ({@link #shared()}); tests of one JVM run one after another,
 * so a test sets the switches it needs at its start and reads counts as differences.
 *
 * <p>It uses the JOSE library of the server's dependencies ({@code com.nimbusds}), never the app's classes.
 */
public final class CraftedOidcProvider {

    /** The client ID the context gives the app ({@code OIDC_CLIENT_ID}). */
    public static final String CLIENT_ID = "popdesk-crafted-client";

    /** The client secret the context gives the app; 32 bytes or more, so it can key HS256. Never in a log. */
    public static final String CLIENT_SECRET = "rst-crafted-client-secret-3f9a1d7c5e2b8a64";

    /** The issuer's path on the server. */
    public static final String PATH = "/crafted";

    /** The endpoints the provider counts requests of. */
    public enum Endpoint {
        DISCOVERY("/.well-known/openid-configuration"),
        JWKS("/jwks"),
        TOKEN("/token");

        private final String path;

        Endpoint(String path) {
            this.path = path;
        }
    }

    /** One request to the token endpoint: its form, decoded. */
    public record TokenRequest(Map<String, String> form) {

        public String code() {
            return form.get("code");
        }

        public String codeVerifier() {
            return form.get("code_verifier");
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static CraftedOidcProvider shared;

    private final HttpServer server;
    private final RSAKey rsa = rsaKey("rsa-1");
    private final ECKey ec = ecKey("ec-1");
    private final List<JWK> published = new CopyOnWriteArrayList<>(List.of(rsa, ec));
    private final Map<String, String> tokens = new ConcurrentHashMap<>();
    private final Map<Endpoint, Integer> counts = new ConcurrentHashMap<>();
    private final List<TokenRequest> tokenRequests = new CopyOnWriteArrayList<>();
    private volatile int discoveryStatus;
    private volatile int tokenStatus;

    private CraftedOidcProvider() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "crafted-oidc-provider");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", this::handle);
        server.start();
    }

    /** The provider of this JVM, started on first use. */
    public static synchronized CraftedOidcProvider shared() {
        if (shared == null) {
            shared = new CraftedOidcProvider();
        }
        return shared;
    }

    /** {@code http://127.0.0.1:{port}/crafted}: the value of {@code OIDC_ISSUER}. */
    public String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + PATH;
    }

    // Switches and counts

    /** From now on the discovery document answers {@code status} with an empty JSON object. */
    public void discoveryAnswers(int status) {
        discoveryStatus = status;
    }

    /** From now on the token endpoint answers {@code status} with {@code {"error": ...}}, whatever the code. */
    public void tokenAnswers(int status) {
        tokenStatus = status;
    }

    /** Back to answering normally. Keys added with {@link #publish} stay in the JWKS. */
    public void reset() {
        discoveryStatus = 0;
        tokenStatus = 0;
    }

    /** How many requests the endpoint got since the provider started. */
    public int requests(Endpoint endpoint) {
        return counts.getOrDefault(endpoint, 0);
    }

    /** The token requests that named {@code code}, oldest first. */
    public List<TokenRequest> tokenRequests(String code) {
        return tokenRequests.stream().filter(r -> code.equals(r.code())).toList();
    }

    // Codes

    /** A new code nobody gave yet, {@code inv-code-...}. */
    public static String newCode() {
        return "inv-code-" + Unique.number();
    }

    /**
     * Makes up a code whose token request gets {@code idToken} (with an access token), every time it is asked for,
     * and returns the code.
     */
    public String give(String idToken) {
        String code = newCode();
        tokens.put(code, idToken);
        return code;
    }

    // Keys and tokens

    /** A new RSA key with {@code kid}, not in the JWKS. */
    public static RSAKey rsaKey(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A new P-256 key with {@code kid}, not in the JWKS. */
    public static ECKey ecKey(String kid) {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The RSA key in the JWKS. */
    public RSAKey rsa() {
        return rsa;
    }

    /** Adds the public part of {@code key} to the JWKS from now on. */
    public void publish(JWK key) {
        published.add(key);
    }

    /** {@code claims} signed RS256 with the RSA key of the JWKS. */
    public String rs256(Map<String, Object> claims) {
        return signed(claims, JWSAlgorithm.RS256, rsa.getKeyID(), signer(rsa));
    }

    /** {@code claims} signed ES256 with the P-256 key of the JWKS. */
    public String es256(Map<String, Object> claims) {
        return signed(claims, JWSAlgorithm.ES256, ec.getKeyID(), signer(ec));
    }

    /** {@code claims} with {@code alg: none} and no signature. */
    public static String unsigned(Map<String, Object> claims) {
        return new PlainObject(new PlainHeader(), new Payload(json(claims))).serialize();
    }

    /** {@code claims} signed HS256 with the client secret, under the {@code kid} of the RSA key of the JWKS. */
    public String hs256(Map<String, Object> claims) {
        try {
            return signed(claims, JWSAlgorithm.HS256, rsa.getKeyID(),
                    new MACSigner(CLIENT_SECRET.getBytes(StandardCharsets.UTF_8)));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code claims} signed RS256 with {@code key}, under {@code kid} (which need not be the key's). */
    public static String signed(Map<String, Object> claims, RSAKey key, String kid) {
        return signed(claims, JWSAlgorithm.RS256, kid, signer(key));
    }

    private static String signed(Map<String, Object> claims, JWSAlgorithm alg, String kid, JWSSigner signer) {
        JWSObject jws = new JWSObject(new JWSHeader.Builder(alg).keyID(kid).type(JOSEObjectType.JWT).build(),
                new Payload(json(claims)));
        try {
            jws.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jws.serialize();
    }

    private static JWSSigner signer(JWK key) {
        try {
            return key instanceof ECKey e ? new ECDSASigner(e) : new RSASSASigner((RSAKey) key);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String json(Map<String, Object> claims) {
        return JSON.writeValueAsString(claims);
    }

    // The server

    private void handle(HttpExchange exchange) throws IOException {
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String path = exchange.getRequestURI().getPath();
        Endpoint endpoint = null;
        for (Endpoint e : Endpoint.values()) {
            if (path.equals(PATH + e.path)) {
                endpoint = e;
            }
        }
        if (endpoint == null) {
            answer(exchange, 404, Map.of("error", "not_found"));
            return;
        }
        counts.merge(endpoint, 1, Integer::sum);
        switch (endpoint) {
            case DISCOVERY -> {
                if (discoveryStatus != 0) {
                    answer(exchange, discoveryStatus, Map.of());
                } else {
                    answer(exchange, 200, discovery());
                }
            }
            case JWKS -> answer(exchange, 200, new JWKSet(List.copyOf(published)).toJSONObject(true));
            case TOKEN -> token(exchange, body);
        }
    }

    private void token(HttpExchange exchange, String body) throws IOException {
        TokenRequest request = new TokenRequest(form(body));
        tokenRequests.add(request);
        if (tokenStatus != 0) {
            answer(exchange, tokenStatus, Map.of("error", tokenStatus == 400 ? "invalid_grant" : "server_error"));
            return;
        }
        String idToken = request.code() == null ? null : tokens.get(request.code());
        if (idToken == null) {
            answer(exchange, 400, Map.of("error", "invalid_grant"));
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("access_token", "rst-crafted-access-" + Unique.number());
        out.put("token_type", "Bearer");
        out.put("expires_in", 300);
        out.put("scope", "openid email profile");
        out.put("id_token", idToken);
        answer(exchange, 200, out);
    }

    private Map<String, Object> discovery() {
        String issuer = issuer();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("issuer", issuer);
        out.put("authorization_endpoint", issuer + "/authorize");
        out.put("token_endpoint", issuer + Endpoint.TOKEN.path);
        out.put("jwks_uri", issuer + Endpoint.JWKS.path);
        out.put("response_types_supported", List.of("code"));
        out.put("subject_types_supported", List.of("public"));
        out.put("id_token_signing_alg_values_supported", List.of("RS256", "ES256"));
        out.put("scopes_supported", List.of("openid", "email", "profile"));
        out.put("token_endpoint_auth_methods_supported", List.of("client_secret_basic", "client_secret_post"));
        out.put("code_challenge_methods_supported", List.of("S256"));
        return out;
    }

    private static Map<String, String> form(String body) {
        return OidcProvider.query(URI.create("http://form/?" + body));
    }

    private static void answer(HttpExchange exchange, int status, Map<String, ?> json) throws IOException {
        byte[] out = JSON.writeValueAsString(json).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }
}
