package io.popdesk.helpdesk.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Audit rows are inserted in one class (slice 1b premortem 3): a row inserted anywhere else would
 * have no place in the hash chain, or one computed another way. The check reads the production
 * sources, because the statement is SQL text, which the compiled classes do not show as a call.
 */
class AuditInsertPlaceTest {

    private static final Path SOURCES = Path.of("src", "main", "java");

    /** SQL that inserts into the audit table, also when a string is split after the keywords. */
    private static final Pattern INSERTS = Pattern.compile("insert\\s+into[\\s\"+]+audit_log\\b",
            Pattern.CASE_INSENSITIVE);

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("S1B-AC-43 only AuditChainRepository holds SQL that inserts into audit_log; the app and the "
            + "commands in the container go through it")
    void onlyTheChainInsertsAuditRows() throws IOException {
        List<String> writers;
        try (Stream<Path> files = Files.walk(SOURCES)) {
            writers = files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> INSERTS.matcher(read(file)).find())
                    .map(file -> SOURCES.relativize(file).toString().replace('\\', '/')).sorted().toList();
        }

        assertThat(writers).containsExactly("io/popdesk/helpdesk/audit/AuditChainRepository.java");
    }

    @Test
    @DisplayName("S1B-AC-43 the pattern finds a statement that is split over two string literals")
    void patternFindsASplitStatement() {
        assertThat(INSERTS.matcher("\"insert into \"\n        + \"audit_log (tenant_id\"").find()).isTrue();
        assertThat(INSERTS.matcher("select count(*) from audit_log").find()).isFalse();
    }
}
