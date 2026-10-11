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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The audit log of an app that runs in a container, through the admin API: a JSON Lines export
 * without a filter, checked as a hash chain while it is read, and the integrity check.
 *
 * <p>The chain is computed by {@code support.AuditChainMath}. A file of 100,000 lines is read line
 * by line; only the lines a test asks for are kept.
 *
 * <p>Every failure is an {@link AssertionError}.
 */
final class AuditFile {

    static final String LOG = "/api/console/audit-log";

    /** The {@code prevHash} of the first entry of a chain. */
    static final String ZERO_HASH = "0".repeat(64);

    static final String HASH_PATTERN = "^[0-9a-f]{64}$";

    private static final JsonMapper JSON = JsonMapper.builder().build();

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
                JsonNode line = parse(text);
                long seq = number(line, "seq");
                String prevHash = text(line, "prevHash");
                String hash = text(line, "hash");
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
                ids.add(number(line, "id"));
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

    /** Runs the integrity check and expects "all entries match": 200, valid, a count, no reason, no entry. */
    static JsonNode assertValid(Person admin) {
        JsonNode answer = admin.client().post(LOG + "/verify").send().expectStatus(200).json();
        assertThat(answer.path("valid").isBoolean() && answer.path("valid").booleanValue())
                .as("valid in the integrity check's answer %s", answer).isTrue();
        assertThat(number(answer, "checkedCount")).as("checkedCount in %s", answer).isPositive();
        assertThat(answer.path("reason").isNull()).as("reason is null in %s", answer).isTrue();
        assertThat(answer.path("brokenEntry").isNull()).as("brokenEntry is null in %s", answer).isTrue();
        return answer;
    }

    static JsonNode parse(String text) {
        try {
            return JSON.readTree(text);
        } catch (JacksonException e) {
            throw new AssertionError("Not JSON: " + text, e);
        }
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString()) {
            throw new AssertionError("No text field '" + field + "' in " + node);
        }
        return value.stringValue();
    }

    static long number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber()) {
            throw new AssertionError("No whole-number field '" + field + "' in " + node);
        }
        return value.asLong();
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
