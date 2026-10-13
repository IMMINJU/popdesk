package io.popdesk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The production Java sources ({@code server/src/main/java}) as text, for the structure tests that
 * look at what bytecode does not keep (catch blocks, string values). Comments are blanked, so a word
 * in a comment is never a finding.
 */
final class ProductionSources {

    private static final Path ROOT = Path.of("src", "main", "java");

    private ProductionSources() {}

    /**
     * Each source file's text by its path under {@code src/main/java} (forward slashes), with
     * comments turned into spaces. With {@code keepLiterals} false the contents of string, text
     * block, and character literals are spaces too. Line breaks stay where they were.
     */
    static Map<String, String> read(boolean keepLiterals) {
        Map<String, String> out = new TreeMap<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(ROOT)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path file : files) {
                out.put(ROOT.relativize(file).toString().replace('\\', '/'),
                        blank(Files.readString(file), keepLiterals));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + ROOT.toAbsolutePath(), e);
        }
        assertThat(out).as("Java files under %s", ROOT.toAbsolutePath()).isNotEmpty();
        return out;
    }

    /** {@code helpdesk/account/Sessions.java} as {@code io.popdesk.helpdesk.account.Sessions}. */
    static String className(String path) {
        return path.substring(0, path.length() - ".java".length()).replace('/', '.');
    }

    static String blank(String source, boolean keepLiterals) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                end = end < 0 ? n : end;
                spaces(out, source, i, end);
                i = end;
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                spaces(out, source, i, end);
                i = end;
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                while (end > 0 && source.charAt(end - 1) == '\\') {
                    end = source.indexOf("\"\"\"", end + 1);
                }
                end = end < 0 ? n : end + 3;
                literal(out, source, i, end, 3, keepLiterals);
                i = end;
            } else if (c == '"' || c == '\'') {
                int end = i + 1;
                while (end < n && source.charAt(end) != c && source.charAt(end) != '\n') {
                    end += source.charAt(end) == '\\' ? 2 : 1;
                }
                end = Math.min(n, end + 1);
                literal(out, source, i, end, 1, keepLiterals);
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static void literal(StringBuilder out, String source, int from, int to, int quote, boolean keep) {
        if (keep || to - from < 2 * quote) {
            out.append(source, from, to);
        } else {
            out.append(source, from, from + quote);
            spaces(out, source, from + quote, to - quote);
            out.append(source, to - quote, to);
        }
    }

    private static void spaces(StringBuilder out, String source, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = source.charAt(i);
            out.append(c == '\n' || c == '\r' ? c : ' ');
        }
    }
}
