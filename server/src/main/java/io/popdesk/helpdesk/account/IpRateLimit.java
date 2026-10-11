package io.popdesk.helpdesk.account;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * At most 20 sign-in attempts per client IP in 60 seconds (COM-8, S0-AC-24). Counted in memory only,
 * never stored (PII-1); popdesk runs one instance (D-23). The window is measured on the app's clock.
 */
@Component
class SignInRateLimit {

    static final int LIMIT = 20;
    static final Duration WINDOW = Duration.ofSeconds(60);

    /** Above this many addresses, windows that have ended are dropped on the next attempt. */
    private static final int SWEEP_ABOVE = 10_000;

    private final Map<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();
    private final Clock clock;

    SignInRateLimit(Clock clock) {
        this.clock = clock;
    }

    /**
     * Counts an attempt from {@code ip}. Empty when it may go on; else the seconds until it may try
     * again, and the attempt is not counted.
     */
    OptionalLong attempt(String ip) {
        Instant now = clock.instant();
        Instant start = now.minus(WINDOW);
        if (attempts.size() > SWEEP_ABOVE) {
            attempts.values().removeIf(q -> {
                synchronized (q) {
                    return q.isEmpty() || !q.peekLast().isAfter(start);
                }
            });
        }
        Deque<Instant> window = attempts.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && !window.peekFirst().isAfter(start)) {
                window.pollFirst();
            }
            if (window.size() >= LIMIT) {
                long wait = Duration.between(now, window.peekFirst().plus(WINDOW)).toSeconds() + 1;
                return OptionalLong.of(Math.max(1, Math.min(WINDOW.toSeconds(), wait)));
            }
            window.addLast(now);
            return OptionalLong.empty();
        }
    }
}
