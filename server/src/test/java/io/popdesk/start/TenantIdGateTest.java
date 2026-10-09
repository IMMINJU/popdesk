package io.popdesk.start;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.BareAppTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Both {@code tenant_id} gates fail on each negative fixture of
 * {@code src/test/resources/start/tenant-id/<case>}: the schema rule, on a scratch schema where the
 * fixture ran, and {@code scripts/ci/check-tenant-id.py}, on the fixture folder.
 */
class TenantIdGateTest extends BareAppTest {

    @Test
    @DisplayName("S0-AC-103 a table without tenant_id fails both gates")
    void tableWithoutTenantId() {
        assertBothGatesFail("missing", "widget");
    }

    @Test
    @DisplayName("S0-AC-103 tenant_id with DROP NOT NULL fails both gates")
    void droppedNotNull() {
        assertBothGatesFail("drop-not-null", "widget");
    }

    @Test
    @DisplayName("S0-AC-103 a table made with (LIKE x) from a table without tenant_id fails both gates")
    void tableLikeAnother() {
        assertBothGatesFail("like", "tenant_copy");
    }

    private void assertBothGatesFail(String fixture, String badTable) {
        Path folder = Repository.root().resolve("server/src/test/resources/start/tenant-id/" + fixture);

        String schema = "test_tenant_" + fixture.replace('-', '_');
        db.schemaFixture("drop schema if exists " + schema + " cascade; create schema " + schema);
        try {
            for (Path sql : sqlFiles(folder)) {
                db.schemaFixture("set search_path to " + schema + "; " + read(sql));
            }
            List<String> violations = TenantIdRule.violations(TenantIdRule.read(db, schema));
            assertThat(violations).as("schema rule on fixture %s", fixture)
                    .anyMatch(v -> v.startsWith(badTable + ":"));
        } finally {
            db.schemaFixture("drop schema if exists " + schema + " cascade");
        }

        Result script = python(Repository.root().resolve("scripts/ci/check-tenant-id.py").toString(), folder.toString());
        assertThat(script.exitCode()).as("check-tenant-id.py on %s: %s", fixture, script.output()).isNotZero();
        assertThat(script.output()).as("check-tenant-id.py names %s", badTable).contains(badTable);
    }

    private record Result(int exitCode, String output) {}

    private static Result python(String... args) {
        List<String> command = new ArrayList<>(List.of("python"));
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean ended = process.waitFor(60, TimeUnit.SECONDS);
            if (!ended) {
                process.destroyForcibly();
                throw new AssertionError("python did not end within 60 seconds: " + command);
            }
            return new Result(process.exitValue(), output);
        } catch (IOException e) {
            throw new AssertionError("Could not run " + command, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted running " + command, e);
        }
    }

    private static List<Path> sqlFiles(Path folder) {
        try (var files = Files.list(folder)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
