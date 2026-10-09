package io.popdesk.ops;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.springframework.core.env.Environment;

/**
 * Reads {@code /actuator/prometheus} on the management port and parses the text format into
 * samples. In tests Spring Boot puts the management port on a free port and publishes it as
 * {@code local.management.port}.
 *
 * <p>The metric names the tests use are listed in {@link Names}. The criteria do not name the
 * metrics; these names are what the ops tab proposes (see its report), so the implementation and
 * the Grafana dashboard (S0-AC-79) use the same ones.
 */
final class Prometheus {

    /** Proposed metric names, as they appear in the Prometheus text format. */
    static final class Names {

        /** Spring Boot's request timer: count, sum, and max per uri, method, status. */
        static final String REQUESTS_COUNT = "http_server_requests_seconds_count";

        static final String REQUESTS_SUM = "http_server_requests_seconds_sum";

        /** Outbox rows waiting to be sent, label {@code channel}. */
        static final String OUTBOX_QUEUED = "popdesk_outbox_queued";

        /** Outbox rows that stopped retrying, label {@code channel}. */
        static final String OUTBOX_FAILED = "popdesk_outbox_failed";

        /** Outbox rows sent, a counter, label {@code channel}. */
        static final String OUTBOX_SENT = "popdesk_outbox_sent_total";

        /** When each cleanup job last ran, Unix seconds, label {@code job}. */
        static final String CLEANUP_LAST_RUN = "popdesk_cleanup_last_run_seconds";

        /** Rows a cleanup job deleted (a gauge or a counter; both names start with this), label {@code job}. */
        static final String CLEANUP_DELETED_PREFIX = "popdesk_cleanup_deleted";

        /** Size of the database, bytes. */
        static final String DB_SIZE = "popdesk_db_size_bytes";

        /** Size of all attachments of the team, bytes. */
        static final String ATTACHMENTS_SIZE = "popdesk_attachments_bytes";

        /** Free space on the data volume, bytes. */
        static final String VOLUME_FREE = "popdesk_data_volume_free_bytes";

        private Names() {}
    }

    /** One sample: {@code name{labels} value}. */
    record Sample(String name, Map<String, String> labels, double value, String line) {

        String label(String key) {
            return labels.get(key);
        }
    }

    /** One scrape: the raw text and its samples. */
    record Scrape(String text, List<Sample> samples) {

        List<Sample> named(String name) {
            return samples.stream().filter(s -> s.name().equals(name)).toList();
        }

        List<Sample> where(Predicate<Sample> filter) {
            return samples.stream().filter(filter).toList();
        }

        /** The sample of {@code name} whose {@code channel} label is {@code channel}, ignoring case. */
        Optional<Sample> forChannel(String name, String channel) {
            return named(name).stream()
                    .filter(s -> channel.equalsIgnoreCase(s.label("channel")))
                    .findFirst();
        }

        /** The value of the one sample named {@code name}; fails with an AssertionError when there is not exactly one. */
        double single(String name) {
            List<Sample> found = named(name);
            if (found.size() != 1) {
                throw new AssertionError("Expected one sample of " + name + " but found " + found.size() + ": "
                        + found.stream().map(Sample::line).toList());
            }
            return found.getFirst().value();
        }

        /** Sum of {@code name} over the samples that match. */
        double sum(String name, Predicate<Sample> filter) {
            return named(name).stream().filter(filter).mapToDouble(Sample::value).sum();
        }
    }

    private final Environment environment;

    Prometheus(Environment environment) {
        this.environment = environment;
    }

    /** The management port. Fails with an AssertionError when the app publishes none. */
    int managementPort() {
        String port = environment.getProperty("local.management.port");
        if (port == null) {
            throw new AssertionError("The app has no management port (local.management.port is not set)");
        }
        try {
            return Integer.parseInt(port);
        } catch (NumberFormatException e) {
            throw new AssertionError("local.management.port is not a number: " + port, e);
        }
    }

    /** {@code GET /actuator/prometheus} on the management port. Expects 200 and the text format. */
    Scrape scrape() {
        ApiResponse response = new ApiClient("localhost", managementPort()).get("/actuator/prometheus")
                .header("Accept", "text/plain")
                .send()
                .expectStatus(200);
        return new Scrape(response.text(), parse(response.text()));
    }

    static List<Sample> parse(String text) {
        List<Sample> out = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            out.add(parseLine(line));
        }
        return out;
    }

    private static Sample parseLine(String line) {
        int brace = line.indexOf('{');
        int space = line.indexOf(' ');
        String name;
        Map<String, String> labels = new LinkedHashMap<>();
        int rest;
        if (brace >= 0 && (space < 0 || brace < space)) {
            name = line.substring(0, brace);
            int i = brace + 1;
            while (i < line.length() && line.charAt(i) != '}') {
                int eq = line.indexOf('=', i);
                if (eq < 0 || eq + 1 >= line.length() || line.charAt(eq + 1) != '"') {
                    throw new AssertionError("Not a Prometheus sample line: " + line);
                }
                String key = line.substring(i, eq).strip();
                StringBuilder value = new StringBuilder();
                int j = eq + 2;
                for (; j < line.length() && line.charAt(j) != '"'; j++) {
                    char c = line.charAt(j);
                    if (c == '\\' && j + 1 < line.length()) {
                        char next = line.charAt(++j);
                        value.append(next == 'n' ? '\n' : next);
                    } else {
                        value.append(c);
                    }
                }
                labels.put(key, value.toString());
                i = j + 1;
                if (i < line.length() && line.charAt(i) == ',') {
                    i++;
                }
            }
            rest = i + 1;
        } else {
            if (space < 0) {
                throw new AssertionError("Not a Prometheus sample line: " + line);
            }
            name = line.substring(0, space);
            rest = space;
        }
        String[] parts = line.substring(Math.min(rest, line.length())).strip().split("\\s+");
        double value;
        try {
            value = switch (parts[0].toLowerCase(Locale.ROOT)) {
                case "nan" -> Double.NaN;
                case "+inf", "inf" -> Double.POSITIVE_INFINITY;
                case "-inf" -> Double.NEGATIVE_INFINITY;
                default -> Double.parseDouble(parts[0]);
            };
        } catch (NumberFormatException e) {
            throw new AssertionError("Not a Prometheus sample line: " + line, e);
        }
        return new Sample(name, Map.copyOf(labels), value, line);
    }
}
