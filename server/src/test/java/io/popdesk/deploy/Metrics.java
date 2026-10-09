package io.popdesk.deploy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Samples of one metric from Prometheus text ({@code /actuator/prometheus}). */
final class Metrics {

    /** One line: the labels as written between the braces, and the value. */
    record Sample(String labels, double value) {

        /** The value of one label, or null. */
        String label(String name) {
            Matcher m = Pattern.compile("(?:^|,)" + Pattern.quote(name) + "=\"([^\"]*)\"").matcher(labels);
            return m.find() ? m.group(1) : null;
        }
    }

    private Metrics() {}

    static List<Sample> samples(String text, String metric) {
        Pattern line = Pattern.compile("^" + Pattern.quote(metric) + "(?:\\{(.*)})?\\s+(\\S+)(?:\\s+\\d+)?$");
        List<Sample> out = new ArrayList<>();
        for (String l : text.split("\n")) {
            Matcher m = line.matcher(l.strip());
            if (m.matches()) {
                out.add(new Sample(m.group(1) == null ? "" : m.group(1), Double.parseDouble(m.group(2).replace("+Inf", "Infinity").replace("-Inf", "-Infinity"))));
            }
        }
        return out;
    }
}
