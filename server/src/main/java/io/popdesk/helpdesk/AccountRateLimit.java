package io.popdesk.helpdesk;

import io.popdesk.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Per-account limits over 60 seconds (COM-8): the same count for every session and address of an
 * account. Counted in memory only; popdesk runs one instance (D-23). The window is measured on the
 * app's clock. Services call {@link #count} right after the sign-in check and before they look up the
 * target, so a 429 never tells whether a target exists.
 */
@Component
public class AccountRateLimit {

    static final Duration WINDOW = Duration.ofSeconds(60);

    /** Above this many accounts, windows that have ended are dropped on the next request. */
    private static final int SWEEP_ABOVE = 10_000;

    /** What is counted, and how many fit in one window. */
    public enum Action {
        /** Filing an issue (S0-AC-50). */
        ISSUE(10),
        /** Adding a comment (S0-AC-55). */
        COMMENT(30),
        /** Uploading an attachment (S0-AC-65). */
        ATTACHMENT(30);

        final int limit;

        Action(int limit) {
            this.limit = limit;
        }
    }

    private record Key(Action action, long accountId) {}

    private final Map<Key, Deque<Instant>> requests = new ConcurrentHashMap<>();
    private final Clock clock;

    AccountRateLimit(Clock clock) {
        this.clock = clock;
    }

    /**
     * Counts one request of {@code action} by the account. Over the limit, throws 429 with the time
     * until the oldest request in the window leaves it, and does not count the request.
     */
    public void count(Action action, long accountId) {
        Instant now = clock.instant();
        Instant start = now.minus(WINDOW);
        if (requests.size() > SWEEP_ABOVE) {
            requests.values().removeIf(q -> {
                synchronized (q) {
                    return q.isEmpty() || !q.peekLast().isAfter(start);
                }
            });
        }
        Deque<Instant> window = requests.computeIfAbsent(new Key(action, accountId), k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && !window.peekFirst().isAfter(start)) {
                window.pollFirst();
            }
            if (window.size() >= action.limit) {
                throw ApiException.rateLimited(Duration.between(now, window.peekFirst().plus(WINDOW)));
            }
            window.addLast(now);
        }
    }
}
