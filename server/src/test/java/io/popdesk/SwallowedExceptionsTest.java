package io.popdesk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Production code does not swallow exceptions (ADR D-57). Two findings, read from the sources
 * because bytecode does not keep them (try-with-resources compiles to a catch of {@code Throwable}):
 *
 * <ul>
 *   <li>{@code empty catch}: a catch block with no statement. A comment is not a statement.
 *   <li>{@code catch (Exception)}, {@code catch (Throwable)}: a catch clause that names one of the
 *       two, alone or in a multi-catch. Errors nobody expected go to {@code ApiExceptionHandler}.
 * </ul>
 *
 * <p>A finding is {@code class | form}, once for a class however many times it has the form, so
 * the baseline {@code architecture/swallowed-exceptions.txt} does not move with line numbers. Not
 * seen here: {@code catch (RuntimeException)}, and a catch block that only logs.
 */
class SwallowedExceptionsTest {

    private static final String BASELINE = "/architecture/swallowed-exceptions.txt";

    /** A catch clause: its parameter and whatever follows the opening brace up to the next non-space. */
    private static final Pattern CATCH = Pattern.compile("\\bcatch\\s*\\(([^)]*)\\)\\s*\\{\\s*(\\}?)");

    private static final Set<String> BROAD = Set.of("Exception", "Throwable", "java.lang.Exception",
            "java.lang.Throwable");

    @Test
    void noSwallowedExceptionOutsideTheBaseline() {
        Set<String> found = new TreeSet<>();
        int clauses = 0;
        for (Map.Entry<String, String> file : ProductionSources.read(false).entrySet()) {
            String type = ProductionSources.className(file.getKey());
            Matcher m = CATCH.matcher(file.getValue());
            while (m.find()) {
                clauses++;
                if (!m.group(2).isEmpty()) {
                    found.add(type + " | empty catch");
                }
                for (String caught : caughtTypes(m.group(1))) {
                    if (BROAD.contains(caught)) {
                        found.add(type + " | catch (" + caught.substring(caught.lastIndexOf('.') + 1) + ")");
                    }
                }
            }
        }

        assertThat(clauses).as("catch clauses read from the production sources").isPositive();
        assertThat(Baseline.added(found, BASELINE))
                .as("swallowed exceptions not in %s (all now: %s)", BASELINE, found)
                .isEmpty();
    }

    /** The types of {@code final A | b.B name}: {@code A}, {@code b.B}. */
    private static Set<String> caughtTypes(String parameter) {
        String types = parameter.strip().replaceAll("@\\w+(\\.\\w+)*\\s*", "").replaceFirst("^final\\s+", "")
                .replaceFirst("\\s*\\w+$", "");
        Set<String> out = new TreeSet<>();
        for (String type : types.split("\\|")) {
            out.add(type.replaceAll("\\s+", ""));
        }
        return out;
    }
}
