package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.deploy.AppSteps.Person;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.AuditChainMath;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;

/**
 * The audit log of an app that runs in a container, through the admin API: a JSON Lines export
 * without a filter, checked as a hash chain while it is read.
 *
 * <p>The chain is computed by {@code support.AuditChainMath}, which also has the integrity check. A
 * file of 100,000 lines is read line by line; only the lines a test asks for are kept.
 *
 * <p>Every failure is an {@link AssertionError}.
 */
final class AuditFile {

    static final String LOG = "/api/console/audit-log";

    /**
     * An export that was a whole chain: the IDs of its lines in file order, the {@code seq} and
     * {@code prevHash} of its first line, and the lines the test asked to keep.
     */
    record Chain(List<Long> ids, long firstSeq, String firstPrevHash, List<JsonNode> kept) {}

    private AuditFile() {}

    /**
     * Exports the log as JSON Lines without a filter and checks every line while reading: its
     * {@code hash} is the hash computed again, its {@code prevHash} is the {@code hash} of the line
     * before, and {@code seq} goes up by one.
     */
    static Chain export(Person admin, Predicate<JsonNode> keep) {
        ApiResponse file = admin.client().get(LOG + "/export").query("format", "JSONL").send().expectStatus(200);
        List<Long> ids = new ArrayList<>();
        List<JsonNode> kept = new ArrayList<>();
        long firstSeq = -1;
        String firstPrevHash = null;
        long seqBefore = -1;
        String hashBefore = null;
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(file.body()),
                StandardCharsets.UTF_8))) {
            for (String text = lines.readLine(); text != null; text = lines.readLine()) {
                if (text.isBlank()) {
                    continue;
                }
                JsonNode line = AuditChainMath.parse(text);
                long seq = AuditChainMath.number(line, "seq");
                String prevHash = AuditChainMath.text(line, "prevHash");
                String hash = AuditChainMath.text(line, "hash");
                if (!hash.equals(AuditChainMath.hash(prevHash, line))) {
                    throw new AssertionError("The hash of seq " + seq + " is not SHA-256 of its prevHash and "
                            + AuditChainMath.chainJson(line) + ": " + text);
                }
                if (hashBefore == null) {
                    firstSeq = seq;
                    firstPrevHash = prevHash;
                } else if (seq != seqBefore + 1 || !prevHash.equals(hashBefore)) {
                    throw new AssertionError("The line after seq " + seqBefore + " (hash " + hashBefore
                            + ") does not follow it: " + text);
                }
                seqBefore = seq;
                hashBefore = hash;
                ids.add(AuditChainMath.number(line, "id"));
                if (keep.test(line)) {
                    kept.add(line);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(ids).as("lines of the export").isNotEmpty();
        return new Chain(ids, firstSeq, firstPrevHash, kept);
    }

    /** A time the API or the database wrote, as an instant at microsecond precision. */
    static Instant time(String written) {
        try {
            return OffsetDateTime.parse(written).toInstant().truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeParseException e) {
            throw new AssertionError("Not a time: '" + written + "'", e);
        }
    }
}
