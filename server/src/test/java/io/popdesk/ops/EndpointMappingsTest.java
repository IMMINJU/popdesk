package io.popdesk.ops;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.BareAppTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The endpoints the running app maps, held to the committed contract files (S0-AC-91, S0-AC-97).
 * The contract files only show what springdoc sees: an endpoint marked {@code @Hidden}, outside the
 * two groups' paths, or left out of a group's {@code pathsToMatch} is in no contract and in no other
 * test. Spring Boot's {@code mappings} endpoint lists every handler of the app's dispatcher servlet
 * whatever springdoc sees.
 *
 * <p>A context of its own: it exposes {@code mappings} on the management port, which the app does not
 * do. Every mapping under {@code /api/} is an operation of a contract file and the reverse
 * ({@code METHOD path}, path variable names ignored). Outside {@code /api/} the app maps only GET or
 * HEAD (the screens), besides {@code /error}. No mapped path holds {@code password}, {@code reset}, or
 * {@code credential}: the admin password reset is a command only.
 */
@TestPropertySource(properties = {
        "management.endpoints.web.exposure.include=mappings",
        "management.endpoint.mappings.access=read-only",
})
class EndpointMappingsTest extends BareAppTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options",
            "trace");

    private static final List<String> CONTRACTS = List.of("openapi-portal.json", "openapi-console.json");

    @Autowired
    Environment environment;

    /** One mapping of a handler method: its HTTP methods (empty: any) and one path pattern. */
    record Mapping(Set<String> methods, String pattern) {

        List<String> operations() {
            Set<String> each = methods.isEmpty() ? Set.of("ANY") : methods;
            return each.stream().map(m -> m + " " + normalized(pattern)).toList();
        }
    }

    /** A path with every path variable written as {@code {}}. */
    private static String normalized(String path) {
        return path.replaceAll("\\{[^}]*}", "{}");
    }

    private static Set<String> contractOperations() {
        Set<String> out = new TreeSet<>();
        for (String name : CONTRACTS) {
            Path file = Path.of("..", "docs", "integrate", name);
            JsonNode paths;
            try {
                paths = JSON.readTree(Files.readString(file)).path("paths");
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read " + file.toAbsolutePath(), e);
            }
            assertThat(paths.isObject()).as("paths of %s", name).isTrue();
            for (String path : paths.propertyNames()) {
                for (String method : paths.path(path).propertyNames()) {
                    if (METHODS.contains(method)) {
                        out.add(method.toUpperCase(Locale.ROOT) + " " + normalized(path));
                    }
                }
            }
        }
        return out;
    }

    private int managementPort() {
        String port = environment.getProperty("local.management.port");
        if (port == null) {
            throw new AssertionError("The app has no management port (local.management.port is not set)");
        }
        return Integer.parseInt(port);
    }

    /** Every handler-method mapping of every dispatcher servlet in the app's contexts. */
    private List<Mapping> mappings() {
        JsonNode body = new ApiClient("localhost", managementPort()).get("/actuator/mappings").send()
                .expectStatus(200).json();
        List<Mapping> out = new ArrayList<>();
        for (JsonNode context : body.path("contexts")) {
            for (JsonNode servlet : context.path("mappings").path("dispatcherServlets")) {
                for (JsonNode m : servlet) {
                    JsonNode conditions = m.path("details").path("requestMappingConditions");
                    if (conditions.isMissingNode() || conditions.isNull()) {
                        continue;
                    }
                    Set<String> methods = new TreeSet<>();
                    conditions.path("methods").forEach(x -> methods.add(x.asString("").toUpperCase(Locale.ROOT)));
                    conditions.path("patterns").forEach(p -> out.add(new Mapping(methods, p.asString(""))));
                }
            }
        }
        return out;
    }

    private static boolean ownedByBoot(String pattern) {
        return pattern.equals("/error") || pattern.equals("/actuator") || pattern.startsWith("/actuator/");
    }

    @Test
    @DisplayName("S0-AC-91 every endpoint the app maps under /api/ is an operation of a contract file, and every "
            + "operation is mapped")
    void apiMappingsAreTheContract() {
        Set<String> contract = contractOperations();
        List<Mapping> mappings = mappings();
        Set<String> mapped = new TreeSet<>();
        mappings.stream().filter(m -> m.pattern().startsWith("/api/")).forEach(m -> mapped.addAll(m.operations()));

        assertThat(contract).as("operations in the contract files").isNotEmpty();
        assertThat(mapped).as("operations the app maps under /api/, held to the contract files")
                .containsExactlyInAnyOrderElementsOf(contract);
    }

    @Test
    @DisplayName("S0-AC-91 outside /api/ the app maps only GET and HEAD, besides Spring Boot's own paths")
    void otherMappingsOnlyRead() {
        List<Mapping> mappings = mappings();

        assertThat(mappings).as("handler mappings of the app").isNotEmpty();
        assertThat(mappings.stream().filter(m -> !m.pattern().startsWith("/api/") && !ownedByBoot(m.pattern())))
                .as("mappings outside /api/ with a method other than GET or HEAD")
                .allMatch(m -> !m.methods().isEmpty() && Set.of("GET", "HEAD").containsAll(m.methods()));
    }

    @Test
    @DisplayName("S0-AC-97 no endpoint the app maps resets a password: no path holds password, reset, or credential")
    void noPasswordResetPath() {
        List<Mapping> mappings = mappings();

        assertThat(mappings).as("handler mappings of the app").isNotEmpty();
        assertThat(mappings.stream().map(Mapping::pattern).filter(p -> !ownedByBoot(p)))
                .as("mapped paths")
                .noneMatch(p -> p.toLowerCase(Locale.ROOT).matches(".*(password|reset|credential).*"));
    }
}
