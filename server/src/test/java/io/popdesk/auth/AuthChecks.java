package io.popdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.DbInspector;
import io.popdesk.support.DbInspector.Column;
import io.popdesk.support.Fixtures.App;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/**
 * Helpers for the sign-in, cookie, and header tests of this package.
 *
 * <p>The app counts sign-in attempts per client IP and trusts {@code X-Forwarded-For}
 * from loopback by default. Each {@code ApiClient} already sends an address of its own;
 * the sign-in helpers here name the address on each request, so a test that counts attempts knows
 * exactly which IP they go to.
 */
final class AuthChecks {

    static final String SIGN_IN = "/api/public/sign-in";

    private static final AtomicInteger NEXT_IP = new AtomicInteger();

    private AuthChecks() {}

    /** An address no other test uses, from 100.64.0.0/10, which is not in the default trusted ranges. */
    static String freshIp() {
        int n = NEXT_IP.getAndIncrement();
        return "100.127." + (n / 250) + "." + (n % 250 + 1);
    }

    /** One sign-in attempt from {@code ip}, whatever the answer. */
    static ApiResponse signIn(ApiClient client, String email, String password, String ip) {
        return client.post(SIGN_IN)
                .header("X-Forwarded-For", ip)
                .json(Map.of("email", email, "password", password))
                .send();
    }

    /** Signs in from a fresh IP, expects 204, and reads who it is from {@code GET .../me}. */
    static Session signedIn(ApiClient client, App app, String email, String password) {
        signIn(client, email, password, freshIp()).expectStatus(204);
        ApiResponse me = client.get(app.api() + "/me").expect(200);
        Role role = app == App.PORTAL ? Role.CUSTOMER : Role.valueOf(me.jsonText("role"));
        return new Session(client, app, me.jsonLong("id"), me.jsonText("email"), me.jsonText("name"), password,
                role);
    }

    static void assertError(ApiResponse response, int status, String code) {
        response.expectStatus(status);
        assertThat(response.errorCode()).as("code of %s", response.request()).isEqualTo(code);
    }

    /** The {@code Set-Cookie} headers of a response other than the CSRF cookie: the session cookie. */
    static List<String> sessionCookies(ApiResponse response) {
        return response.headers("Set-Cookie").stream()
                .filter(h -> !h.startsWith(ApiClient.CSRF_COOKIE + "="))
                .toList();
    }

    static String cookieName(String setCookie) {
        return setCookie.substring(0, setCookie.indexOf('=')).strip();
    }

    static String cookieValue(String setCookie) {
        String pair = setCookie.split(";", 2)[0];
        return pair.substring(pair.indexOf('=') + 1).strip();
    }

    /** The attributes of a {@code Set-Cookie} header, names in lower case; a flag maps to "". */
    static Map<String, String> cookieAttributes(String setCookie) {
        Map<String, String> out = new LinkedHashMap<>();
        String[] parts = setCookie.split(";");
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i].strip();
            if (part.isEmpty()) {
                continue;
            }
            int eq = part.indexOf('=');
            String name = (eq < 0 ? part : part.substring(0, eq)).strip().toLowerCase(Locale.ROOT);
            out.put(name, eq < 0 ? "" : part.substring(eq + 1).strip());
        }
        return out;
    }

    /** CSP with {@code default-src 'self'}, no inline scripts, {@code frame-ancestors 'none'}; nosniff; no referrer. */
    static void assertSecurityHeaders(ApiResponse response) {
        String what = response.request() + " -> " + response.status();
        String csp = response.header("Content-Security-Policy");
        assertThat(csp).as("Content-Security-Policy of %s", what).isNotBlank();
        Map<String, List<String>> directives = cspDirectives(csp);
        assertThat(directives.get("default-src")).as("default-src of %s: %s", what, csp).contains("'self'");
        assertThat(directives.get("frame-ancestors")).as("frame-ancestors of %s: %s", what, csp)
                .containsExactly("'none'");
        List<String> scripts = directives.getOrDefault("script-src", directives.get("default-src"));
        assertThat(scripts).as("script sources of %s: %s", what, csp).doesNotContain("'unsafe-inline'");
        for (String name : List.of("script-src-elem", "script-src-attr")) {
            if (directives.containsKey(name)) {
                assertThat(directives.get(name)).as("%s of %s: %s", name, what, csp).doesNotContain("'unsafe-inline'");
            }
        }
        assertThat(response.header("X-Content-Type-Options")).as("X-Content-Type-Options of %s", what)
                .isEqualToIgnoringCase("nosniff");
        assertThat(response.header("Referrer-Policy")).as("Referrer-Policy of %s", what)
                .isEqualToIgnoringCase("no-referrer");
    }

    private static Map<String, List<String>> cspDirectives(String csp) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String directive : csp.split(";")) {
            List<String> words = Arrays.stream(directive.strip().split("\\s+")).filter(w -> !w.isEmpty()).toList();
            if (!words.isEmpty()) {
                out.putIfAbsent(words.getFirst().toLowerCase(Locale.ROOT), words.subList(1, words.size()));
            }
        }
        return out;
    }

    /** {@code text/plain; charset=utf-8}, however the server spaces and cases it. */
    static String normalizedContentType(ApiResponse response) {
        String type = response.header("Content-Type");
        return type == null ? null : type.replace(" ", "").toLowerCase(Locale.ROOT);
    }

    /** The {@code (field, code)} pairs of a 400 body's {@code fields}. */
    static List<String> fieldErrors(ApiResponse response) {
        List<String> out = new ArrayList<>();
        for (JsonNode field : response.json().path("fields")) {
            out.add(field.path("field").asString() + ":" + field.path("code").asString());
        }
        return out;
    }

    /**
     * Every row in the public schema that holds {@code value} anywhere in any column, read as text.
     * Schema-independent, for "is this value stored" checks.
     */
    static List<Map<String, Object>> rowsContaining(DbInspector db, String value) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String table : db.tables()) {
            List<Column> columns = db.columns(table);
            if (columns.isEmpty()) {
                continue;
            }
            String where = columns.stream()
                    .map(c -> "position(? in \"" + c.name() + "\"::text) > 0")
                    .collect(Collectors.joining(" or "));
            Object[] args = Collections.nCopies(columns.size(), value).toArray();
            for (Map<String, Object> row : db.rows("select * from \"" + table + "\" where " + where, args)) {
                Map<String, Object> tagged = new LinkedHashMap<>(row);
                tagged.put("(table)", table);
                out.add(tagged);
            }
        }
        return out;
    }
}
