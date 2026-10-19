package io.popdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.OidcProvider;
import io.popdesk.support.OidcProvider.Redirect;
import io.popdesk.support.TestClock;
import io.popdesk.support.Unique;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/**
 * The OIDC sign-in of slice 11 as a browser does it, over HTTP without following redirects: the start
 * ({@code GET /api/public/oidc/start}) answers 302 to the provider and sets the {@value #COOKIE} cookie, the person
 * logs in at the provider ({@link OidcProvider#logIn}), the provider answers 302 to the app's callback with
 * {@code code} and {@code state}, and the browser opens the callback with its cookie.
 *
 * <p>The app's clock stands still at a time of its own ({@link TestClock}), not the real time the provider writes
 * into its tokens. So every ID token this flow asks for carries {@code iat} and {@code nbf} at the app's time and
 * {@code exp} an hour after it ({@link #claims}); a test that wants other times sets them.
 *
 * <p>Every step that expects an answer fails with an AssertionError otherwise. Before the slice is built the start
 * answers 501, so a test fails at its first step, on an assertion.
 */
public final class OidcFlow {

    public static final String START = "/api/public/oidc/start";
    public static final String CALLBACK = "/api/public/oidc/callback";
    public static final String OPTIONS = "/api/public/sign-in-options";

    /** The cookie that ties a sign-in to the browser on http ({@code __Host-POPDESK_OIDC} on https). */
    public static final String COOKIE = "POPDESK_OIDC";

    /** The eight query parameters of the authorization address (S11-AC-04). */
    public static final Set<String> AUTHORIZATION_QUERY = Set.of("response_type", "client_id",
            "redirect_uri", "scope", "state", "nonce", "code_challenge", "code_challenge_method");

    private final OidcProvider provider;
    private final TestClock clock;

    public OidcFlow(OidcProvider provider, TestClock clock) {
        this.provider = provider;
        this.clock = clock;
    }

    public OidcProvider provider() {
        return provider;
    }

    /** A new provider subject that looks like a person's name, not like a token: {@code alice-12}. */
    public static String newSub() {
        return "alice-" + Unique.number();
    }

    // Claims

    /**
     * The claims of a person with a verified email: {@code email}, {@code email_verified: true}, and the times of
     * {@link #times}. A new map; change what the test is about.
     */
    public Map<String, Object> claims(String email) {
        Map<String, Object> claims = times();
        claims.put("email", email);
        claims.put("email_verified", true);
        return claims;
    }

    /** Only the times: {@code iat} and {@code nbf} at the app's clock, {@code exp} an hour later, in epoch seconds. */
    public Map<String, Object> times() {
        long now = clock.instant().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iat", now);
        claims.put("nbf", now);
        claims.put("exp", now + Duration.ofHours(1).toSeconds());
        return claims;
    }

    /** {@code instant} in epoch seconds, for a claim. */
    public static long seconds(Instant instant) {
        return instant.getEpochSecond();
    }

    // Steps

    /** {@code GET /api/public/oidc/start} in this browser, whatever the answer. */
    public ApiResponse start(ApiClient browser) {
        return browser.get(START).send();
    }

    /** {@code GET /api/public/oidc/start?next=...} in this browser, whatever the answer. */
    public ApiResponse start(ApiClient browser, String next) {
        return browser.get(START).query("next", next).send();
    }

    /**
     * Starts and expects the 302 to the provider's authorization endpoint with the {@value #COOKIE} cookie; returns
     * where it sent the browser.
     */
    public Started started(ApiClient browser) {
        return started(start(browser));
    }

    /** As {@link #started(ApiClient)}, with {@code next}. */
    public Started started(ApiClient browser, String next) {
        return started(start(browser, next));
    }

    /** The answer of a start, which must be the 302 to the provider with the {@value #COOKIE} cookie. */
    public Started started(ApiResponse start) {
        start.expectStatus(302);
        String location = start.header("Location");
        assertThat(location).as("Location of %s", start).isNotNull()
                .startsWith(provider.issuer() + "/");
        assertThat(start.setCookie(COOKIE)).as("%s cookie set by %s: %s", COOKIE, start, start.headers("Set-Cookie"))
                .isNotNull();
        URI uri = URI.create(location);
        return new Started(start, uri, OidcProvider.query(uri));
    }

    /** Logs in at the provider as {@code sub} with {@code claims}; returns the redirect back to the app. */
    public Redirect logIn(Started started, String sub, Map<String, Object> claims) {
        return provider.logIn(started.location().toString(), sub, claims);
    }

    /** {@code GET /api/public/oidc/callback?code=...&state=...} in this browser, whatever the answer. */
    public ApiResponse callback(ApiClient browser, Redirect back) {
        return callback(browser, back.query());
    }

    /** The callback with any query (for example {@code error} and {@code state}), whatever the answer. */
    public ApiResponse callback(ApiClient browser, Map<String, String> query) {
        ApiRequest request = browser.get(CALLBACK);
        query.forEach(request::query);
        return request.send();
    }

    /** The whole sign-in in this browser: start, login at the provider, callback. Returns the callback's answer. */
    public ApiResponse signIn(ApiClient browser, String sub, Map<String, Object> claims) {
        return callback(browser, logIn(started(browser), sub, claims));
    }

    /** The whole sign-in with {@code next}. */
    public ApiResponse signIn(ApiClient browser, String next, String sub, Map<String, Object> claims) {
        return callback(browser, logIn(started(browser, next), sub, claims));
    }

    /**
     * Signs in and expects a session: 302 to {@code /} and {@code GET /api/{app}/me} answers 200. Returns the
     * account ID {@code me} gives.
     */
    public long signedIn(ApiClient browser, String api, String sub, Map<String, Object> claims) {
        ApiResponse back = signIn(browser, sub, claims);
        assertRedirect(browser, back, "/");
        return browser.get(api + "/me").expect(200).jsonLong("id");
    }

    // Answers

    /**
     * The {@code Location} of a 302 as a path of the browser's app: an address on the app's own public origin
     * ({@code http://{host}} or {@code http://{host}:80}) is cut to its path and query; any other stays as it is.
     */
    public static String appLocation(ApiClient browser, ApiResponse response) {
        String location = response.header("Location");
        if (location == null) {
            return null;
        }
        for (String origin : new String[] {"http://" + browser.host() + ":80", "http://" + browser.host()}) {
            if (location.startsWith(origin + "/")) {
                return location.substring(origin.length());
            }
        }
        return location;
    }

    /** A 302 to {@code path} of the browser's app (relative, or on the app's public origin). */
    public static void assertRedirect(ApiClient browser, ApiResponse response, String path) {
        assertThat(response.status()).as("status of %s: %s", response, response.snippet()).isEqualTo(302);
        assertThat(appLocation(browser, response)).as("Location of %s", response).isEqualTo(path);
    }

    /** A 302 back to the sign-in page with {@code oidc=value}: {@code failed}, {@code unavailable}, or {@code busy}. */
    public static void assertBackAtLogin(ApiClient browser, ApiResponse response, String value) {
        assertRedirect(browser, response, "/login?oidc=" + value);
    }

    /** No session in this browser: {@code GET /api/{app}/me} answers 401. */
    public static void assertNoSession(ApiClient browser, String api) {
        ApiResponse me = browser.get(api + "/me").send();
        assertThat(me.status()).as("%s after the sign-in failed: %s", me, me.snippet()).isEqualTo(401);
    }

    /** The field names of a JSON object. */
    public static Set<String> fieldNames(JsonNode object) {
        Set<String> names = new TreeSet<>();
        object.propertyNames().forEach(names::add);
        return names;
    }

    /** The answer of a start that went to the provider: where to, and its query. */
    public record Started(ApiResponse response, URI location, Map<String, String> query) {

        public String state() {
            return query.get("state");
        }

        public String nonce() {
            return query.get("nonce");
        }
    }
}
