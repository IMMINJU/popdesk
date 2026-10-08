package io.popdesk.support;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The app's standard output and standard error, as the test JVM captured them. One bean per test
 * context; {@link #all()} starts where that context started, so it holds the startup log.
 *
 * <p>The buffer is shared by every context in the JVM. A cached context runs for many test classes,
 * so read the part you caused: take a {@link #mark()} before the request and read {@link #since}.
 * Background work of another context (a send loop) can also write between your mark and your read;
 * look for your own values, not for "no line at all".
 */
public final class AppLog {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final int start;

    AppLog(int start) {
        this.start = start;
    }

    /** A position in the log. */
    public record Mark(int position) {}

    /** Everything written since this context started. */
    public String all() {
        return ConsoleCapture.since(start);
    }

    /** The current end of the log. */
    public Mark mark() {
        return new Mark(ConsoleCapture.position());
    }

    /** Everything written after {@code mark}. */
    public String since(Mark mark) {
        return ConsoleCapture.since(mark.position());
    }

    public List<String> lines() {
        return all().lines().toList();
    }

    public List<String> lines(Mark mark) {
        return since(mark).lines().toList();
    }

    /** Lines since this context started that are JSON objects, parsed. Other lines are left out. */
    public List<JsonNode> jsonLines() {
        return json(lines());
    }

    public List<JsonNode> jsonLines(Mark mark) {
        return json(lines(mark));
    }

    /** Waits until a line after {@code mark} contains {@code text}; fails with an AssertionError if none does. */
    public String awaitLine(Mark mark, String text) {
        return Wait.until("a log line containing " + text, () -> lines(mark).stream()
                .filter(line -> line.contains(text))
                .findFirst()
                .orElse(null));
    }

    private static List<JsonNode> json(List<String> lines) {
        List<JsonNode> out = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("{")) {
                continue;
            }
            try {
                JsonNode node = JSON.readTree(trimmed);
                if (node.isObject()) {
                    out.add(node);
                }
            } catch (JacksonException notJson) {
                // A line that only starts with "{" is not a JSON log line; leave it out.
            }
        }
        return out;
    }
}
