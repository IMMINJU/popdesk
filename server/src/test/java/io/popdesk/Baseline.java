package io.popdesk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;

/**
 * A baseline file of a structure test (ADR D-57): the findings that existed when the test was
 * written, one on a line. Empty lines and lines that start with {@code #} are not findings. Lines
 * are only removed from a baseline; a new finding is fixed in the code.
 */
final class Baseline {

    private Baseline() {}

    /** The lines of a classpath resource such as {@code /architecture/open-paths.txt}. */
    static Set<String> read(String resource) {
        try (InputStream in = Baseline.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            Set<String> lines = new TreeSet<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    lines.add(trimmed);
                }
            }
            return lines;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + resource, e);
        }
    }

    /** The findings that are not in the baseline. */
    static Set<String> added(Set<String> found, String resource) {
        Set<String> added = new TreeSet<>(found);
        added.removeAll(read(resource));
        return added;
    }
}
