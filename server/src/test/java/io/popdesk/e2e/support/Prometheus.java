package io.popdesk.e2e.support;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the Prometheus text format from {@code /actuator/prometheus}. */
public final class Prometheus {

    private static final Pattern SAMPLE = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)(\\{(.*)})?\\s+(\\S+)(\\s+\\S+)?$");
    private static final Pattern LABEL = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final String text;

    public Prometheus(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }

    public boolean has(String name) {
        return text.lines().map(SAMPLE::matcher).anyMatch(m -> m.matches() && m.group(1).equals(name));
    }

    /**
     * Sum of every sample of {@code name} whose labels match {@code labels} (values compared
     * ignoring case). 0 when there is none.
     */
    public double sum(String name, Map<String, String> labels) {
        double total = 0;
        for (String line : text.lines().toList()) {
            Matcher m = SAMPLE.matcher(line);
            if (!m.matches() || !m.group(1).equals(name)) {
                continue;
            }
            Map<String, String> have = new HashMap<>();
            if (m.group(3) != null) {
                Matcher l = LABEL.matcher(m.group(3));
                while (l.find()) {
                    have.put(l.group(1), l.group(2));
                }
            }
            boolean match = labels.entrySet().stream().allMatch(e -> e.getValue().equalsIgnoreCase(have.get(e.getKey())));
            if (match) {
                total += Double.parseDouble(m.group(4));
            }
        }
        return total;
    }

    public double sum(String name) {
        return sum(name, Map.of());
    }
}
