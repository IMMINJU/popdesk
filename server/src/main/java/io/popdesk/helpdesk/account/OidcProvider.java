package io.popdesk.helpdesk.account;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.popdesk.notification.OidcSignInState.FailureKind;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The OIDC provider as popdesk reaches it (AUTH-6, D-64): its discovery document, its keys, and its token endpoint.
 * Nothing is asked at startup (S11-AC-02); the first sign-in reads the discovery document, which is kept for an hour.
 * A discovery that fails is kept for 30 seconds, and the starts in that time are refused without asking again, so a
 * provider that is down does not hold request threads (S11-AC-07). Both are measured on the app's clock. Every
 * request has 3 seconds to connect and 10 to read, and follows no redirect.
 *
 * <p>The ID token's signature is checked with the keys of {@code jwks_uri}, by RS256 or ES256 only, so
 * {@code alg: none} and HS256 are refused. The keys are kept as long as the discovery document; a token under a key ID
 * they do not have makes one more request for them (S11-AC-10). The claims are {@link IdTokenClaims}'s, so the
 * decoder checks no time and no claim.
 *
 * <p>No log line here holds an address, a code, a token, or the client secret.
 */
@Component
class OidcProvider {

    static final Duration KEEP = Duration.ofHours(1);
    static final Duration KEEP_FAILURE = Duration.ofSeconds(30);
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(OidcProvider.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The endpoints of the discovery document, checked against the settings. */
    record Metadata(URI authorization, URI token, URI jwks) {}

    /** The provider could not be reached or answered with 5xx or 429: the person sees {@code unavailable}. */
    static final class Unavailable extends RuntimeException {
        Unavailable(String reason) {
            super(reason, null, false, false);
        }
    }

    /** The provider answered, and the sign-in cannot go on: the person sees {@code failed}. */
    static final class Refused extends RuntimeException {
        Refused(String reason) {
            super(reason, null, false, false);
        }
    }

    private record Kept(Metadata metadata, Instant at) {}

    private final OidcSettings settings;
    private final OidcStatus status;
    private final Clock clock;
    private final RestClient http;
    private final RestClientAuthorizationCodeTokenResponseClient tokens;
    private final Keys keys;
    private final NimbusJwtDecoder decoder;
    private final Object discovering = new Object();

    private volatile Kept kept;
    private volatile Instant failedAt;

    OidcProvider(OidcSetup setup, OidcStatus status, Clock clock) {
        this.settings = setup.settings();
        this.status = status;
        this.clock = clock;
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
        requests.setReadTimeout(READ_TIMEOUT);
        this.http = RestClient.builder().requestFactory(requests).build();
        this.tokens = new RestClientAuthorizationCodeTokenResponseClient();
        this.tokens.setRestClient(RestClient.builder().requestFactory(requests)
                .configureMessageConverters(converters -> converters
                        .addCustomConverter(new FormHttpMessageConverter())
                        .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
                .defaultStatusHandler(new TokenErrors())
                .build());
        this.keys = new Keys();
        this.decoder = NimbusJwtDecoder.withJwkSource(keys)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .jwsAlgorithm(SignatureAlgorithm.ES256)
                .jwtProcessorCustomizer(processor -> processor.setJWTClaimsSetVerifier((claims, context) -> {}))
                .build();
        this.decoder.setJwtValidator(jwt -> OAuth2TokenValidatorResult.success());
    }

    /**
     * The discovery document's endpoints: the one kept if it is under an hour old, else read now. Empty when it
     * cannot be read or is wrong (not 200, another {@code issuer}, an endpoint missing, or an {@code http} one for an
     * {@code https} issuer), and for 30 seconds after that without asking again. Only a request that was made and
     * failed counts as a {@code DISCOVERY} failure.
     */
    Optional<Metadata> metadata() {
        Optional<Metadata> known = known(clock.instant());
        if (known != null) {
            return known;
        }
        synchronized (discovering) {
            known = known(clock.instant());
            if (known != null) {
                return known;
            }
            try {
                Metadata metadata = discover();
                kept = new Kept(metadata, clock.instant());
                failedAt = null;
                return Optional.of(metadata);
            } catch (Unavailable | Refused e) {
                failedAt = clock.instant();
                status.failed(FailureKind.DISCOVERY);
                log.warn("OIDC sign-in is unavailable: the provider's discovery document {}. popdesk asks again in "
                        + "{} seconds.", e.getMessage(), KEEP_FAILURE.toSeconds());
                return Optional.empty();
            }
        }
    }

    /** The kept document, empty for a kept failure, or null when it must be read. */
    private Optional<Metadata> known(Instant now) {
        Kept k = kept;
        if (k != null && now.isBefore(k.at().plus(KEEP))) {
            return Optional.of(k.metadata());
        }
        Instant failed = failedAt;
        if (failed != null && now.isBefore(failed.plus(KEEP_FAILURE))) {
            return Optional.empty();
        }
        return null;
    }

    private Metadata discover() {
        JsonNode document = json(settings.discoveryUri(), "could not be read", "did not answer 200");
        if (!settings.issuer().equals(document.path("issuer").asString(null))) {
            throw new Refused("names another issuer than " + OidcSettings.ISSUER);
        }
        URI authorization = endpoint(document, "authorization_endpoint");
        URI token = endpoint(document, "token_endpoint");
        URI jwks = endpoint(document, "jwks_uri");
        return new Metadata(authorization, token, jwks);
    }

    private URI endpoint(JsonNode document, String name) {
        String value = document.path(name).asString(null);
        URI uri;
        try {
            uri = value == null ? null : new URI(value);
        } catch (java.net.URISyntaxException e) {
            uri = null;
        }
        if (uri == null || !uri.isAbsolute() || uri.getHost() == null) {
            throw new Refused("has no usable " + name);
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(scheme.equals("http") && !settings.httpsIssuer())) {
            throw new Refused("gives an " + name + " that is not https for an https issuer");
        }
        return uri;
    }

    /** A JSON object read with GET; {@link Unavailable} when not reached, {@link Refused} when not 200 or not JSON. */
    private JsonNode json(URI uri, String unreachable, String not200) {
        String body;
        try {
            body = http.get().uri(uri).exchange((request, response) -> {
                if (response.getStatusCode().value() != 200) {
                    throw new Refused(not200 + " (HTTP " + response.getStatusCode().value() + ")");
                }
                return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
            });
        } catch (ResourceAccessException e) {
            throw new Unavailable(unreachable + " (" + kind(e) + ")");
        } catch (RestClientException e) {
            throw new Refused(unreachable + " (" + e.getClass().getSimpleName() + ")");
        }
        try {
            JsonNode node = JSON.readTree(body);
            if (node == null || !node.isObject()) {
                throw new Refused("is not a JSON object");
            }
            return node;
        } catch (JacksonException e) {
            throw new Refused("is not JSON");
        }
    }

    /**
     * Exchanges the code for an ID token at the token endpoint, with {@code client_secret_basic} and the PKCE
     * verifier (S11-AC-09). {@link Unavailable} when the endpoint cannot be reached or answers 5xx or 429,
     * {@link Refused} for any other error or an answer without an ID token (S11-AC-13). Either counts as a
     * {@code TOKEN} failure.
     */
    String idToken(Metadata metadata, String code, String state, String verifier, String redirectUri) {
        ClientRegistration client = ClientRegistration.withRegistrationId("oidc")
                .clientId(settings.clientId())
                .clientSecret(settings.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri)
                .scope("openid", "email", "profile")
                .authorizationUri(metadata.authorization().toString())
                .tokenUri(metadata.token().toString())
                .jwkSetUri(metadata.jwks().toString())
                .build();
        OAuth2AuthorizationRequest asked = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(metadata.authorization().toString())
                .clientId(settings.clientId())
                .redirectUri(redirectUri)
                .state(state)
                .attributes(Map.of(PkceParameterNames.CODE_VERIFIER, verifier))
                .build();
        OAuth2AuthorizationResponse answered = OAuth2AuthorizationResponse.success(code)
                .redirectUri(redirectUri)
                .state(state)
                .build();
        OAuth2AccessTokenResponse response;
        try {
            response = tokens.getTokenResponse(new OAuth2AuthorizationCodeGrantRequest(client,
                    new OAuth2AuthorizationExchange(asked, answered)));
        } catch (Unavailable | Refused e) {
            status.failed(FailureKind.TOKEN);
            throw e;
        } catch (OAuth2AuthorizationException e) {
            status.failed(FailureKind.TOKEN);
            if (e.getCause() instanceof ResourceAccessException access) {
                throw new Unavailable("the token endpoint could not be reached (" + kind(access) + ")");
            }
            throw new Refused("the token endpoint's answer could not be read");
        }
        Object idToken = response.getAdditionalParameters().get("id_token");
        if (!(idToken instanceof String text) || text.isEmpty()) {
            status.failed(FailureKind.TOKEN);
            throw new Refused("the token endpoint's answer has no ID token");
        }
        return text;
    }

    /**
     * The claims of an ID token whose signature checks out with the provider's keys, by RS256 or ES256.
     * {@link Unavailable} when the keys cannot be read, {@link Refused} when the token is not a JWS of those
     * algorithms or its signature is wrong. Either counts as an {@code ID_TOKEN} failure. The claims are not checked
     * here ({@link IdTokenClaims}).
     */
    Map<String, Object> signedClaims(Metadata metadata, String idToken) {
        try {
            keys.ready(metadata.jwks());
        } catch (Unavailable e) {
            status.failed(FailureKind.ID_TOKEN);
            throw e;
        }
        try {
            return decoder.decode(idToken).getClaims();
        } catch (JwtException | IllegalArgumentException e) {
            status.failed(FailureKind.ID_TOKEN);
            throw new Refused("the ID token's signature or form is wrong");
        }
    }

    /** An ID token whose claims fail counts as an {@code ID_TOKEN} failure. */
    void claimsFailed() {
        status.failed(FailureKind.ID_TOKEN);
    }

    private static String kind(ResourceAccessException e) {
        Throwable cause = e.getCause();
        if (cause instanceof java.net.http.HttpConnectTimeoutException || cause instanceof java.net.ConnectException) {
            return "no connection";
        }
        if (cause instanceof java.net.http.HttpTimeoutException) {
            return "timed out";
        }
        return cause == null ? "I/O error" : cause.getClass().getSimpleName();
    }

    /** 5xx and 429 of the token endpoint are {@link Unavailable}, any other error {@link Refused} (S11-AC-13). */
    private static final class TokenErrors implements ResponseErrorHandler {

        @Override
        public boolean hasError(ClientHttpResponse response) throws IOException {
            return response.getStatusCode().isError();
        }

        @Override
        public void handleError(URI url, HttpMethod method, ClientHttpResponse response) throws IOException {
            HttpStatusCode code = response.getStatusCode();
            if (code.is5xxServerError() || code.value() == 429) {
                throw new Unavailable("the token endpoint answered HTTP " + code.value());
            }
            throw new Refused("the token endpoint answered HTTP " + code.value());
        }
    }

    /**
     * The provider's keys, read from {@code jwks_uri} and kept as long as the discovery document. When no kept key
     * fits a token, they are read once more for that token (a key the provider added since).
     */
    private final class Keys implements JWKSource<SecurityContext> {

        private volatile URI uri;
        private volatile JWKSet set;
        private volatile Instant at;

        /** Reads the keys unless the ones of {@code jwks} are kept and under an hour old. */
        synchronized void ready(URI jwks) {
            if (!jwks.equals(uri) || set == null || !clock.instant().isBefore(at.plus(KEEP))) {
                uri = jwks;
                fetch();
            }
        }

        private void fetch() {
            JsonNode document = json(uri, "could not be read", "did not answer 200");
            try {
                set = JWKSet.parse(document.toString());
            } catch (ParseException e) {
                throw new Refused("the provider's keys are not a JWK set");
            }
            at = clock.instant();
        }

        @Override
        public List<JWK> get(JWKSelector selector, SecurityContext context) throws KeySourceException {
            List<JWK> found = selector.select(set);
            if (!found.isEmpty()) {
                return found;
            }
            synchronized (this) {
                try {
                    fetch();
                } catch (Unavailable | Refused e) {
                    throw new KeySourceException("The provider's keys could not be read again");
                }
                return selector.select(set);
            }
        }
    }
}
