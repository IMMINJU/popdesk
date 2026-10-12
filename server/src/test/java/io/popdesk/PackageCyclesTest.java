package io.popdesk;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * No new cycle between production packages (ADR D-6, design/process.md "구조 검사").
 *
 * <p>Every package under {@code io.popdesk} is one node. An edge {@code a -> b} exists when a
 * class in {@code a} depends on a class in {@code b}. An edge is cyclic when both ends can reach
 * each other. The cyclic edges that existed when this test was written are listed in
 * {@code architecture/package-cycles.txt}; any other cyclic edge fails.
 *
 * <p>The baseline is per package pair, not per class reference: one more call along a listed edge
 * passes, a new edge that closes or widens a cycle does not. The baseline is test code (locked).
 * The fix for a failure is to move the code or turn the dependency around, not to add a line.
 */
class PackageCyclesTest {

    private static final String ROOT = "io.popdesk";
    private static final String BASELINE = "/architecture/package-cycles.txt";

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    @Test
    void noCyclicPackageEdgeOutsideTheBaseline() throws IOException {
        Set<String> cyclic = cyclicEdges(packageEdges());
        Set<String> known = baseline();

        Set<String> added = new TreeSet<>(cyclic);
        added.removeAll(known);

        assertThat(added)
                .as("package edges that are part of a cycle and not in %s (all cyclic edges now: %s)", BASELINE, cyclic)
                .isEmpty();
    }

    private static Map<String, Set<String>> packageEdges() {
        Map<String, Set<String>> edges = new TreeMap<>();
        for (JavaClass type : PRODUCTION) {
            String from = type.getPackageName();
            edges.computeIfAbsent(from, k -> new TreeSet<>());
            for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
                String to = dependency.getTargetClass().getPackageName();
                if (!to.equals(from) && (to.equals(ROOT) || to.startsWith(ROOT + "."))) {
                    edges.get(from).add(to);
                    edges.computeIfAbsent(to, k -> new TreeSet<>());
                }
            }
        }
        return edges;
    }

    /** Edges whose two ends reach each other, as {@code "a -> b"}. */
    private static Set<String> cyclicEdges(Map<String, Set<String>> edges) {
        Map<String, Set<String>> reach = new HashMap<>();
        for (String start : edges.keySet()) {
            Set<String> seen = new HashSet<>();
            Deque<String> todo = new ArrayDeque<>(edges.get(start));
            while (!todo.isEmpty()) {
                String next = todo.pop();
                if (seen.add(next)) {
                    todo.addAll(edges.get(next));
                }
            }
            reach.put(start, seen);
        }
        Set<String> cyclic = new TreeSet<>();
        edges.forEach((from, targets) -> {
            for (String to : targets) {
                if (reach.get(to).contains(from)) {
                    cyclic.add(from + " -> " + to);
                }
            }
        });
        return cyclic;
    }

    private static Set<String> baseline() throws IOException {
        try (InputStream in = PackageCyclesTest.class.getResourceAsStream(BASELINE)) {
            assertThat(in).as(BASELINE).isNotNull();
            Set<String> lines = new TreeSet<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    lines.add(trimmed);
                }
            }
            return lines;
        }
    }
}
