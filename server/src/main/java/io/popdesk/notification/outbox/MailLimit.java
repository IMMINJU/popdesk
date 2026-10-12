package io.popdesk.notification.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * At most 50 emails an hour to one address (MAIL-6, S4B-AC-10 to S4B-AC-13): a stop for a loop that
 * went wrong, not a quota. Counted are the emails the SMTP server took, by the address they really went
 * to, so in a sandbox all email adds up; a refused attempt is not counted. The send loop and the test
 * email count together.
 *
 * <p>Counted in memory only, on the app's clock: popdesk runs one instance (D-23), and after a restart
 * the count starts at none. The key is a hash of the address in lower case, so no address stays in
 * memory here. {@code popdesk_mail_rate_limited_total} counts the emails that were stopped; it has no
 * label.
 */
@Component
class MailLimit {

    static final int LIMIT = 50;
    static final Duration WINDOW = Duration.ofMinutes(60);

    /** The error kind of a row the limit stopped ({@code outbox.last_error_kind}). */
    static final String ERROR_KIND = "RATE_LIMITED";

    /** Above this many addresses, those with no email left in the window are dropped on the next email. */
    private static final int SWEEP_ABOVE = 10_000;

    private final Map<String, Deque<Instant>> sent = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Counter stopped;

    MailLimit(Clock clock, MeterRegistry registry) {
        this.clock = clock;
        this.stopped = Counter.builder("popdesk.mail.rate.limited")
                .description("Emails not sent because their address had 50 in the last hour")
                .register(registry);
    }

    /**
     * Whether the address has had its 50 emails in the last 60 minutes. Then the email is not to be
     * sent, and this counts it as stopped.
     */
    boolean stops(String address) {
        Deque<Instant> times = sent.get(key(address));
        if (times == null) {
            return false;
        }
        Instant start = clock.instant().minus(WINDOW);
        synchronized (times) {
            dropBefore(times, start);
            if (times.size() < LIMIT) {
                return false;
            }
        }
        stopped.increment();
        return true;
    }

    /** The SMTP server took an email to the address just now. */
    void sent(String address) {
        Instant now = clock.instant();
        Instant start = now.minus(WINDOW);
        if (sent.size() > SWEEP_ABOVE) {
            sent.values().removeIf(times -> {
                synchronized (times) {
                    return times.isEmpty() || !times.peekLast().isAfter(start);
                }
            });
        }
        Deque<Instant> times = sent.computeIfAbsent(key(address), k -> new ArrayDeque<>());
        synchronized (times) {
            dropBefore(times, start);
            times.addLast(now);
        }
    }

    private static void dropBefore(Deque<Instant> times, Instant start) {
        while (!times.isEmpty() && !times.peekFirst().isAfter(start)) {
            times.pollFirst();
        }
    }

    private static String key(String address) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(address.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
