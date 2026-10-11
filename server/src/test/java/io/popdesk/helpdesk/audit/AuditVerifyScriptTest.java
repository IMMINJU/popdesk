package io.popdesk.helpdesk.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.helpdesk.audit.AuditChain.Values;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code docs/integrate/audit-verify.py} against lines the app's own code makes (design section 3):
 * the script is written from the rules in conventions.md alone, so when it agrees with the app the
 * rules as written are enough to check an export.
 *
 * <p>Needs a Python 3 on the path ({@code python3} or {@code python}), as the scripts in
 * {@code scripts/ci} do.
 */
class AuditVerifyScriptTest {

    private static final Path SCRIPT = Path.of("..", "docs", "integrate", "audit-verify.py");

    private record Run(int exit, String output) {}

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Entries of the kinds of value the log holds, chained from {@code firstSeq} on, as export lines. */
    private static List<String> lines(long firstSeq, String firstPrevHash) {
        Map<String, Object> renamed = new LinkedHashMap<>();
        renamed.put("name", Map.of("from", "주식회사 \"가나\" \\ 다", "to", "Line\nbreak\ttab \u0001 😀 é"));
        renamed.put("legalName", Map.of("from", "a/b", "to", "</script>"));
        Map<String, Object> invited = new LinkedHashMap<>();
        invited.put("role", "CUSTOMER");
        invited.put("companyCodes", List.of("ACME", "ZETA"));
        invited.put("expiresAt", Instant.parse("2026-10-15T03:15:00.000001Z"));
        invited.put("member", true);
        invited.put("note", null);
        invited.put("count", 12345678901234L);
        // Keys whose order by UTF-16 code units is not their order by code points.
        invited.put("😀", 1);
        invited.put("דּ", 2);
        List<Values> entries = List.of(
                AuditChain.values(AuditAction.FIRST_ADMIN_CREATED, 0, null, null,
                        Map.of("type", "ACCOUNT", "accountId", 1), Map.of("email", "admin@example.test"),
                        Instant.parse("2026-10-08T03:15:00Z")),
                AuditChain.values(AuditAction.SIGN_IN_SUCCEEDED, 1, "관리자", "admin@example.test",
                        Map.of("type", "ACCOUNT", "accountId", 1), null, Instant.parse("2026-10-08T03:15:00.5Z")),
                AuditChain.values(AuditAction.COMPANY_UPDATED, 1, "관리자", "admin@example.test",
                        Map.of("type", "COMPANY", "code", "ACME"), renamed,
                        Instant.parse("2026-10-08T03:15:00.123456Z")),
                AuditChain.values(AuditAction.INVITATION_CREATED, 1, "관리자", "admin@example.test",
                        Map.of("type", "ACCOUNT", "accountId", 2), invited, Instant.parse("2026-10-08T03:15:01Z")));
        List<String> lines = new ArrayList<>();
        long seq = firstSeq;
        String prevHash = firstPrevHash;
        for (Values entry : entries) {
            String hash = AuditChain.hash(prevHash, entry);
            lines.add(AuditExportLines.jsonLine(new AuditRow(100 + seq, seq, prevHash, hash, entry.action(),
                    entry.actor(), entry.target(), entry.changes(), entry.occurredAt())));
            prevHash = hash;
            seq++;
        }
        return lines;
    }

    private static String python() {
        for (String command : List.of("python3", "python")) {
            try {
                Process process = new ProcessBuilder(command, "-c", "import sys; sys.exit(0 if sys.version_info[0] "
                        + "== 3 else 1)").redirectErrorStream(true).start();
                process.getInputStream().readAllBytes();
                if (process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) {
                    return command;
                }
            } catch (IOException notThere) {
                // Try the next name.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while looking for Python", e);
            }
        }
        throw new AssertionError("No Python 3 on the path (python3 or python); audit-verify.py cannot be run");
    }

    private static Run verify(Path directory, List<String> lines) throws IOException, InterruptedException {
        Path file = directory.resolve("export.jsonl");
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        Process process = new ProcessBuilder(python(), "-I", SCRIPT.toString(), file.toString())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("audit-verify.py did not end: " + output);
        }
        return new Run(process.exitValue(), output);
    }

    @Test
    @DisplayName("S1B-AC-24 the script written from the documented rules finds the chain of the app's own export "
            + "lines whole, from seq 1 and from a later start")
    void scriptAgreesWithTheApp(@TempDir Path directory) throws IOException, InterruptedException {
        List<String> fromTheStart = lines(1, AuditChain.ZERO_HASH);
        List<String> afterRetention = lines(41, sha256("the last entry removed"));

        Run whole = verify(directory, fromTheStart);
        Run later = verify(directory, afterRetention);

        assertThat(whole.exit()).as("exit code for a whole chain: %s", whole.output()).isZero();
        assertThat(whole.output()).contains("OK: 4 entries, last seq 4");
        assertThat(later.exit()).as("exit code for a chain that starts at seq 41: %s", later.output()).isZero();
        assertThat(later.output()).contains("starts at seq 41").contains("OK: 4 entries, last seq 44");
    }

    @Test
    @DisplayName("S1B-AC-24 the script names the line of a changed value, of a missing line, and of a first "
            + "entry that does not start from 64 zeros")
    void scriptFindsABrokenChain(@TempDir Path directory) throws IOException, InterruptedException {
        List<String> changed = new ArrayList<>(lines(1, AuditChain.ZERO_HASH));
        changed.set(2, changed.get(2).replace("ACME", "ACMF"));
        List<String> missing = new ArrayList<>(lines(1, AuditChain.ZERO_HASH));
        missing.remove(1);
        List<String> wrongStart = lines(1, sha256("not the start"));

        Run forChanged = verify(directory, changed);
        Run forMissing = verify(directory, missing);
        Run forWrongStart = verify(directory, wrongStart);

        assertThat(changed.get(2)).as("the changed line").contains("ACMF");
        assertThat(forChanged.exit()).as(forChanged.output()).isEqualTo(1);
        assertThat(forChanged.output()).contains("BROKEN at line 3");
        assertThat(forMissing.exit()).as(forMissing.output()).isEqualTo(1);
        assertThat(forMissing.output()).contains("BROKEN at line 2");
        assertThat(forWrongStart.exit()).as(forWrongStart.output()).isEqualTo(1);
        assertThat(forWrongStart.output()).contains("BROKEN at line 1");
    }
}
