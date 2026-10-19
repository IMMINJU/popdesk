package io.popdesk.mailin;

import io.popdesk.support.ApiClient;
import org.springframework.core.env.Environment;

/**
 * Reads the mail-in metrics from {@code /actuator/prometheus} on the app's management port. The names are those of
 * S12-AC-35 in the Prometheus text form: {@code popdesk.mailin.messages} becomes {@code popdesk_mailin_messages_total}.
 */
final class MailInMetrics {

    private final int managementPort;

    MailInMetrics(Environment environment) {
        String port = environment.getProperty("local.management.port");
        if (port == null) {
            throw new AssertionError("The app published no local.management.port");
        }
        this.managementPort = Integer.parseInt(port);
    }

    /** The scrape as text. */
    String scrape() {
        return new ApiClient("localhost", managementPort).get("/actuator/prometheus").expect(200).text();
    }

    /**
     * The sum of every sample whose name starts with {@code prefix} and whose labels hold {@code label} (or any, when it
     * is empty); 0 when there is none.
     */
    double sum(String prefix, String label) {
        double sum = 0;
        for (String line : scrape().lines().toList()) {
            if (line.startsWith("#") || !line.startsWith(prefix) || !line.contains(label)) {
                continue;
            }
            sum += Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
        }
        return sum;
    }
}
