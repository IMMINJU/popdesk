package io.popdesk.boundary;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.boundary.BoundaryList.Entry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Holds {@link BoundaryList} to the committed contract files {@code docs/integrate/openapi-portal.json}
 * and {@code openapi-console.json} and to the test classes. No app is started: the files are
 * generated from the code and CI checks that they match (S0-AC-91), so an endpoint added to the
 * code without a line in the list fails here.
 */
class BoundaryListTest {

    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options",
            "trace");

    private static final List<String> CONTRACTS = List.of("openapi-portal.json", "openapi-console.json");

    /** Every operation of both contract files as {@code METHOD path}, with the files it is in. */
    private static Map<String, Set<String>> contractOperations() {
        Map<String, Set<String>> out = new TreeMap<>();
        for (String name : CONTRACTS) {
            JsonNode paths = read(Path.of("..", "docs", "integrate", name)).path("paths");
            assertThat(paths.isObject()).as("paths of " + name).isTrue();
            for (String path : paths.propertyNames()) {
                for (String method : paths.path(path).propertyNames()) {
                    if (METHODS.contains(method)) {
                        out.computeIfAbsent(method.toUpperCase(Locale.ROOT) + " " + path, k -> new TreeSet<>())
                                .add(name);
                    }
                }
            }
        }
        return out;
    }

    private static JsonNode read(Path file) {
        try {
            return JsonMapper.builder().build().readTree(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file.toAbsolutePath(), e);
        }
    }

    private static Map<String, List<Entry>> listByOperation() {
        return BoundaryList.ENTRIES.stream().collect(Collectors.groupingBy(Entry::operation, TreeMap::new,
                Collectors.toList()));
    }

    @Test
    @DisplayName("S0-AC-93 every operation of both contract files is in the boundary list")
    void everyContractOperationIsListed() {
        Map<String, Set<String>> contract = contractOperations();

        assertThat(contract).as("operations in the contract files").isNotEmpty();
        assertThat(contract.keySet()).as("contract operations missing from BoundaryList")
                .isSubsetOf(listByOperation().keySet());
    }

    @Test
    @DisplayName("S0-AC-93 every operation in the boundary list is in a contract file, and only once in the list")
    void everyListedOperationIsInTheContract() {
        Map<String, List<Entry>> listed = listByOperation();

        assertThat(listed.keySet()).as("BoundaryList operations that no contract file has")
                .isSubsetOf(contractOperations().keySet());
        assertThat(listed.entrySet().stream().filter(e -> e.getValue().size() > 1).map(Map.Entry::getKey).toList())
                .as("operations listed more than once").isEmpty();
    }

    @Test
    @DisplayName("S0-AC-93 each listed operation names its boundary tests or gives the reason it has none, not both")
    void eachOperationHasTestsOrReason() {
        List<String> wrong = new ArrayList<>();
        for (Entry e : BoundaryList.ENTRIES) {
            boolean hasTests = !e.tests().isEmpty();
            boolean hasReason = e.noBoundary() != null && !e.noBoundary().isBlank();
            if (hasTests == hasReason) {
                wrong.add(e.operation());
            }
        }

        assertThat(wrong).as("operations with neither tests nor a reason, or with both").isEmpty();
    }

    @Test
    @DisplayName("S0-AC-93 every test the boundary list names is a test method of a test class")
    void everyNamedTestExists() {
        List<String> missing = BoundaryList.ENTRIES.stream()
                .flatMap(e -> e.tests().stream().map(t -> e.operation() + " -> " + t + ": " + problemWith(t)))
                .filter(line -> !line.endsWith(": ok"))
                .toList();

        assertThat(BoundaryList.ENTRIES.stream().mapToLong(e -> e.tests().size()).sum()).as("tests named")
                .isPositive();
        assertThat(missing).as("named tests that do not exist").isEmpty();
    }

    /** "ok", or what is wrong with {@code package.Class#method} under {@code io.popdesk}. */
    private static String problemWith(String test) {
        int hash = test.indexOf('#');
        if (hash <= 0 || hash == test.length() - 1) {
            return "not written as package.Class#method";
        }
        String className = "io.popdesk." + test.substring(0, hash);
        String methodName = test.substring(hash + 1);
        Class<?> type;
        try {
            type = Class.forName(className, false, BoundaryListTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            return "no class " + className;
        }
        for (Method m : type.getDeclaredMethods()) {
            if (m.getName().equals(methodName)
                    && (m.isAnnotationPresent(Test.class) || m.isAnnotationPresent(ParameterizedTest.class))) {
                return "ok";
            }
        }
        return "no @Test method " + methodName + " in " + className;
    }
}
