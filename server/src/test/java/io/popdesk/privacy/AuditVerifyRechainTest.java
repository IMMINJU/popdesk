package io.popdesk.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.AuditChainMath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code docs/integrate/audit-verify.py} on copies received before and after a recalculation, mixed in one JSON Lines
 * file (S10A-AC-24, conventions.md "Anonymization and the chain"). In the shape of {@code
 * helpdesk.audit.AuditVerifyScriptTest}, but the lines are made with {@link AuditChainMath} from the documented
 * rules, without the app's classes.
 *
 * <p>The chain here: seq 1 to 5, in which account 7 is the target of the invitation (seq 2) and signs in (seq 4) and
 * admin 9 changes a company (seq 3). Anonymizing account 7 gives {@code v1}: seq 2 to 5 computed again,
 * {@code AUDIT_RECHAINED} at seq 6 for 2-5, {@code ACCOUNT_ANONYMIZED} by admin 9 at seq 7. Anonymizing admin 9 after
 * that gives {@code v2}: seq 3 to 7 computed again, {@code AUDIT_RECHAINED} at seq 8 for 3-7, and seq 9. {@code v0} is
 * seq 1 to 5 before anything was anonymized.
 *
 * <p>The script's line for a range is {@code rechained F-T at R} (design.md 5절); a break is
 * {@code BROKEN at line N}, as before.
 */
class AuditVerifyRechainTest {

    private static final Path SCRIPT = Path.of("..", "docs", "integrate", "audit-verify.py");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String NAME = "삭제된 사용자";

    private record Run(int exit, String output) {}

    // Values

    private static ObjectNode actor(long accountId, String name, String email) {
        ObjectNode actor = JSON.createObjectNode().put("accountId", accountId);
        if (name == null) {
            actor.putNull("name").putNull("email");
        } else {
            actor.put("name", name).put("email", email);
        }
        return actor;
    }

    private static ObjectNode entry(String action, ObjectNode actor, ObjectNode target, JsonNode changes,
            String occurredAt) {
        ObjectNode entry = JSON.createObjectNode().put("action", action);
        entry.set("actor", actor);
        entry.set("target", target);
        entry.set("changes", changes);
        entry.put("occurredAt", occurredAt);
        return entry;
    }

    private static ObjectNode account(long id) {
        return JSON.createObjectNode().put("type", "ACCOUNT").put("accountId", id);
    }

    private static ObjectNode company(String code) {
        return JSON.createObjectNode().put("type", "COMPANY").put("code", code);
    }

    /** Seq 1 to 5 before any anonymization. */
    private static List<ObjectNode> values() {
        ObjectNode admin = actor(1, "관리자", "admin@example.test");
        ObjectNode invited = JSON.createObjectNode().put("email", "seven@example.test").put("name", "Seven")
                .put("role", "CUSTOMER");
        ObjectNode renamed = JSON.createObjectNode();
        renamed.set("name", JSON.createObjectNode().put("from", "Old").put("to", "New"));
        return new ArrayList<>(List.of(
                entry("COMPANY_CREATED", admin, company("ACME"), JSON.createObjectNode().put("name", "Acme"),
                        "2026-10-08T03:15:00.000000Z"),
                entry("INVITATION_CREATED", admin, account(7), invited, "2026-10-08T03:15:01.000000Z"),
                entry("COMPANY_UPDATED", actor(9, "Nine", "nine@example.test"), company("ACME"), renamed,
                        "2026-10-08T03:15:02.000000Z"),
                entry("SIGN_IN_SUCCEEDED", actor(7, "Seven", "seven@example.test"), account(7),
                        JSON.nullNode(), "2026-10-08T03:15:03.000000Z"),
                entry("COMPANY_CREATED", admin, company("ZETA"), JSON.createObjectNode().put("name", "Zeta"),
                        "2026-10-08T03:15:04.000000Z")));
    }

    /** The person fields of {@code accountId} set to the anonymous values (acceptance.md "말의 뜻"). */
    private static List<ObjectNode> anonymized(List<ObjectNode> values, long accountId) {
        List<ObjectNode> out = new ArrayList<>();
        for (ObjectNode value : values) {
            ObjectNode copy = value.deepCopy();
            String email = "deleted-" + accountId + "@invalid";
            if (copy.path("actor").path("accountId").asLong(-1) == accountId) {
                ((ObjectNode) copy.get("actor")).put("name", NAME).put("email", email);
            }
            if (copy.path("target").path("accountId").asLong(-1) == accountId && copy.get("changes").isObject()) {
                ObjectNode changes = (ObjectNode) copy.get("changes");
                if (changes.has("name")) {
                    changes.put("name", NAME);
                }
                if (changes.has("email")) {
                    changes.put("email", email);
                }
            }
            out.add(copy);
        }
        return out;
    }

    /** Export lines of the values from seq 1, chained from 64 zeros. */
    private static List<ObjectNode> chained(List<ObjectNode> values) {
        List<ObjectNode> out = new ArrayList<>();
        String prev = AuditChainMath.ZERO_HASH;
        long seq = 1;
        for (ObjectNode value : values) {
            ObjectNode line = line(value, seq++, prev);
            out.add(line);
            prev = line.get("hash").asString();
        }
        return out;
    }

    private static ObjectNode line(ObjectNode value, long seq, String prevHash) {
        ObjectNode line = JSON.createObjectNode().put("id", 100 + seq).put("seq", seq).put("prevHash", prevHash);
        line.setAll(value);
        line.put("hash", AuditChainMath.hash(prevHash, value));
        return line;
    }

    private static ObjectNode rechained(long from, long to, String headFrom, String headTo, String at) {
        ObjectNode changes = JSON.createObjectNode().put("fromSeq", from).put("toSeq", to);
        changes.set("headHash", JSON.createObjectNode().put("from", headFrom).put("to", headTo));
        return entry("AUDIT_RECHAINED", actor(0, null, null), JSON.createObjectNode().put("type", "AUDIT_LOG"),
                changes, at);
    }

    /** The three versions of the chain, each a list of lines from seq 1. */
    private record Versions(List<ObjectNode> v0, List<ObjectNode> v1, List<ObjectNode> v2) {

        /** Seq {@code from} to {@code to} of a version. */
        static List<ObjectNode> seqs(List<ObjectNode> version, int from, int to) {
            return new ArrayList<>(version.subList(from - 1, to));
        }
    }

    private static Versions versions() {
        List<ObjectNode> v0 = chained(values());
        List<ObjectNode> first = anonymized(values(), 7);
        List<ObjectNode> v1head = chained(first);
        first.add(rechained(2, 5, hashOf(v0, 5), hashOf(v1head, 5), "2026-10-08T03:16:00.000000Z"));
        first.add(entry("ACCOUNT_ANONYMIZED", actor(9, "Nine", "nine@example.test"), account(7), JSON.nullNode(),
                "2026-10-08T03:16:00.000001Z"));
        List<ObjectNode> v1 = chained(first);
        List<ObjectNode> second = anonymized(first, 9);
        List<ObjectNode> v2head = chained(second);
        second.add(rechained(3, 7, hashOf(v1, 7), hashOf(v2head, 7), "2026-10-08T03:17:00.000000Z"));
        second.add(entry("ACCOUNT_ANONYMIZED", actor(1, "관리자", "admin@example.test"), account(9), JSON.nullNode(),
                "2026-10-08T03:17:00.000001Z"));
        return new Versions(v0, v1, chained(second));
    }

    private static String hashOf(List<ObjectNode> version, int seq) {
        return version.get(seq - 1).get("hash").asString();
    }

    @SafeVarargs
    private static List<ObjectNode> concat(List<ObjectNode>... parts) {
        return Stream.of(parts).flatMap(List::stream).toList();
    }

    // Running the script

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

    private static Run verify(Path directory, List<ObjectNode> lines) throws IOException, InterruptedException {
        Path file = directory.resolve("copies.jsonl");
        StringBuilder text = new StringBuilder();
        for (ObjectNode line : lines) {
            text.append(JSON.writeValueAsString(line)).append('\n');
        }
        Files.writeString(file, text, StandardCharsets.UTF_8);
        Process process = new ProcessBuilder(python(), "-I", SCRIPT.toString(), file.toString())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("audit-verify.py did not end: " + output);
        }
        return new Run(process.exitValue(), output);
    }

    // Whole

    @Test
    @DisplayName("S10A-AC-24 the script finds whole a file of copies from before and after a recalculation, the "
            + "same seq twice with the same hash, the old and the new copy of a seq inside a later range, and two "
            + "overlapping ranges, and names each range as rechained F-T at R")
    void mixedFilesAreWhole(@TempDir Path directory) throws IOException, InterruptedException {
        Versions v = versions();
        List<ObjectNode> mixed = concat(Versions.seqs(v.v0(), 1, 3), Versions.seqs(v.v1(), 4, 7));
        List<ObjectNode> twice = concat(Versions.seqs(v.v0(), 1, 4), Versions.seqs(v.v1(), 3, 7),
                Versions.seqs(v.v1(), 7, 7));
        List<ObjectNode> overlapping = concat(Versions.seqs(v.v0(), 1, 2), Versions.seqs(v.v1(), 3, 6),
                Versions.seqs(v.v2(), 7, 9));

        Run ofMixed = verify(directory, mixed);
        Run ofTwice = verify(directory, twice);
        Run ofOverlapping = verify(directory, overlapping);

        assertThat(AuditChainMath.hash(hashOf(v.v1(), 5), Versions.seqs(v.v1(), 6, 6).getFirst()))
                .as("the AUDIT_RECHAINED line is chained after the new seq 5").isEqualTo(hashOf(v.v1(), 6));
        assertThat(ofMixed.exit()).as("exit code for old and new copies: %s", ofMixed.output()).isZero();
        assertThat(ofMixed.output()).contains("rechained 2-5 at 6").contains("OK").doesNotContain("BROKEN");
        assertThat(ofTwice.exit()).as("exit code with seqs twice: %s", ofTwice.output()).isZero();
        assertThat(ofTwice.output()).contains("rechained 2-5 at 6").doesNotContain("BROKEN");
        assertThat(ofOverlapping.exit()).as("exit code for two ranges: %s", ofOverlapping.output()).isZero();
        assertThat(ofOverlapping.output()).contains("rechained 2-5 at 6").contains("rechained 3-7 at 8")
                .doesNotContain("BROKEN");
    }

    // Broken

    static Stream<Arguments> breaks() {
        Versions v = versions();
        List<ObjectNode> mixedTail = Versions.seqs(v.v1(), 4, 7);

        List<ObjectNode> afterRange = concat(Versions.seqs(v.v0(), 1, 3), Versions.seqs(v.v1(), 4, 6));
        ObjectNode seven = Versions.seqs(v.v1(), 7, 7).getFirst().deepCopy();
        String otherPrev = AuditChainMath.hash(AuditChainMath.ZERO_HASH, seven);
        seven.put("prevHash", otherPrev).put("hash", AuditChainMath.hash(otherPrev, seven));

        List<ObjectNode> wrongHead = new ArrayList<>(Versions.seqs(v.v1(), 1, 5));
        ObjectNode badRechained = rechained(2, 5, hashOf(v.v0(), 5), AuditChainMath.hash("x", seven),
                "2026-10-08T03:16:00.000000Z");
        ObjectNode r = line(badRechained, 6, hashOf(v.v1(), 5));
        wrongHead.add(r);
        ObjectNode anonymizedValues = Versions.seqs(v.v1(), 7, 7).getFirst().deepCopy();
        List.of("id", "seq", "prevHash", "hash").forEach(anonymizedValues::remove);
        wrongHead.add(line(anonymizedValues, 7, r.get("hash").asString()));

        List<ObjectNode> ownHash = new ArrayList<>(concat(Versions.seqs(v.v0(), 1, 3), mixedTail));
        ObjectNode five = ownHash.get(4).deepCopy();
        ((ObjectNode) five.get("target")).put("code", "ZETB");
        ownHash.set(4, five);

        ObjectNode otherSeven = Versions.seqs(v.v1(), 7, 7).getFirst().deepCopy();
        otherSeven.put("occurredAt", "2026-10-08T03:16:00.000002Z");
        otherSeven.put("hash", AuditChainMath.hash(otherSeven.get("prevHash").asString(), otherSeven));

        return Stream.of(
                Arguments.of("a prevHash after the range that is not the hash before it",
                        concat(afterRange, List.of(seven)), 7),
                Arguments.of("an AUDIT_RECHAINED whose prevHash is not headHash.to", wrongHead, 6),
                Arguments.of("a line in the range whose hash does not match its own values",
                        ownHash, 5),
                Arguments.of("the same seq twice with another hash and no later range around it",
                        concat(Versions.seqs(v.v0(), 1, 3), mixedTail, List.of(otherSeven)), 8));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("breaks")
    @DisplayName("S10A-AC-24 in a file of old and new copies the script still reports as a break a mismatch outside "
            + "the ranges, an AUDIT_RECHAINED not chained to its headHash.to, a hash that does not match its line, "
            + "and the same seq twice with another hash outside any later range")
    void otherMismatchesBreak(String what, List<ObjectNode> lines, int brokenLine, @TempDir Path directory)
            throws IOException, InterruptedException {
        Run run = verify(directory, lines);

        assertThat(run.exit()).as("exit code for %s: %s", what, run.output()).isEqualTo(1);
        assertThat(run.output()).as("output for %s", what).contains("BROKEN at line " + brokenLine);
    }
}
