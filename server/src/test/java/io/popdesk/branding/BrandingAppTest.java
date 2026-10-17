package io.popdesk.branding;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The app of the server tests that change the branding or upload a logo (slice 9): "connected"
 * ({@link ConnectedAppTest}, for the sender name of mail), with a context and database of its own, so the one
 * branding of the installation reaches no other class. Other contexts keep the default branding ("popdesk", no
 * accent color, no logo); {@code project.NoStoreTest} relies on that.
 *
 * <p>Every test on this context shares the branding. A test first sets the values it looks at ({@link #save}, with
 * the version it read), deletes the logos it does not upload ({@link #deleteLogos}), and reads only the audit
 * entries of an admin it made itself. It never relies on values another test left.
 */
@TestPropertySource(properties = BrandingAppTest.OWN_CONTEXT)
public abstract class BrandingAppTest extends ConnectedAppTest {

    /** A property the app does not read; it keeps this context apart from the other connected ones. */
    public static final String OWN_CONTEXT = "branding.test.context=branding";

    public static final String PUBLIC = "/api/public/branding";
    public static final String BRANDING = "/api/console/branding";
    public static final String ACCENT = "/api/console/branding/accent";
    public static final String LOGOS = "/api/console/branding/logos/";

    /** The keys of one theme in {@code colors}. */
    public static final List<String> THEME_KEYS = List.of("accent", "accentHover", "onAccent", "accentSubtle");

    /** The default emerald of {@code tokens.css} in the light theme, an accent color every test may send. */
    public static final String EMERALD = "#047857";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonMapper SORTED = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    /** {@code GET /api/console/branding} as the admin, 200. */
    protected static JsonNode branding(Session admin) {
        return admin.client().get(BRANDING).expect(200).json();
    }

    protected static long version(JsonNode branding) {
        JsonNode version = branding.path("version");
        if (!version.isNumber()) {
            throw new AssertionError("no number version in " + branding);
        }
        return version.asLong();
    }

    /** The body of {@code PUT /api/console/branding}; {@code accent} may be null. */
    protected static Map<String, Object> body(long version, String displayName, String accent) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("displayName", displayName);
        body.put("accent", accent);
        return body;
    }

    protected static ApiRequest update(Session admin, Map<String, Object> body) {
        return admin.client().put(BRANDING).json(body);
    }

    /** Reads the version and saves {@code displayName} and {@code accent} with it, 200; gives the answer. */
    protected static JsonNode save(Session admin, String displayName, String accent) {
        return update(admin, body(version(branding(admin)), displayName, accent)).expect(200).json();
    }

    /** A display name no other test of this JVM sends, under 50 characters. */
    protected static String uniqueName() {
        return "Desk " + Unique.number();
    }

    /** Deletes both logos, 204 each (204 also when there was none). */
    protected static void deleteLogos(Session admin) {
        for (String theme : List.of("light", "dark")) {
            admin.client().delete(LOGOS + theme).expect(204);
        }
    }

    /** {@code GET /api/public/branding} on a client, 200. */
    protected static JsonNode publicBranding(ApiClient client) {
        return client.get(PUBLIC).expect(200).json();
    }

    /** {@code GET /api/console/branding/accent?color=…} as the caller. */
    protected static ApiResponse preview(Session caller, String color) {
        return caller.client().get(ACCENT).query("color", color).send();
    }

    /** The {@code fields[]} of an error answer as {@code "field CODE"}. */
    protected static List<String> fields(ApiResponse refused) {
        List<String> out = new ArrayList<>();
        refused.json().path("fields").forEach(f -> out.add(f.path("field").asString(null) + " "
                + f.path("code").asString(null)));
        return out;
    }

    protected static void assertRefused(ApiResponse response, int status, String code) {
        assertThat(response.status()).as("%s: %s", response.request(), response.snippet()).isEqualTo(status);
        assertThat(response.errorCode()).as("code of %s: %s", response.request(), response.snippet())
                .isEqualTo(code);
    }

    /** A 400 {@code INVALID_INPUT} whose {@code fields[]} holds {@code field} with {@code code}. */
    protected static void assertInvalid(ApiResponse response, String field, String code) {
        assertRefused(response, 400, "INVALID_INPUT");
        assertThat(fields(response)).as("fields of %s", response.snippet()).contains(field + " " + code);
    }

    protected static JsonNode json(Object value) {
        return JSON.valueToTree(value);
    }

    /**
     * The text of a JSON value with every object's keys sorted, to compare two values whatever their key order. Not the
     * audit chain's canonical form: that one takes whole numbers only, and contrasts are decimals.
     */
    protected static String canonical(JsonNode value) {
        return SORTED.writeValueAsString(JSON.treeToValue(value, Object.class));
    }

    /** The names of an object's fields, in order. */
    protected static List<String> keys(JsonNode node) {
        return new ArrayList<>(node.propertyNames());
    }

    /** The {@code colors} of an accent color, {@code light} and {@code dark} each in {@link #THEME_KEYS} order. */
    protected static Map<String, Object> colors(List<String> light, List<String> dark) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("light", theme(light));
        out.put("dark", theme(dark));
        return out;
    }

    private static Map<String, Object> theme(List<String> values) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < THEME_KEYS.size(); i++) {
            out.put(THEME_KEYS.get(i), values.get(i));
        }
        return out;
    }

    /**
     * {@code colors} has exactly {@code light} and {@code dark}, each with exactly the four keys, each value
     * {@code #RRGGBB} in capitals.
     */
    protected static void assertMadeValues(JsonNode colors, String what) {
        assertThat(colors.isObject()).as("colors of %s: %s", what, colors).isTrue();
        assertThat(keys(colors)).as("themes of %s", what).containsExactlyInAnyOrder("light", "dark");
        for (String theme : List.of("light", "dark")) {
            JsonNode values = colors.path(theme);
            assertThat(keys(values)).as("%s values of %s", theme, what).containsExactlyInAnyOrderElementsOf(THEME_KEYS);
            for (String key : THEME_KEYS) {
                assertThat(values.path(key).asString("")).as("%s.%s of %s", theme, key, what)
                        .matches("#[0-9A-F]{6}");
            }
        }
    }
}
