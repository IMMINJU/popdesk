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
        /** Uploading an attachment (S0-AC-65), also one filed with a new issue (S1A-AC-66). */
        ATTACHMENT(30),
        /** Uploading a profile photo (S1A-AC-28). */
        PHOTO(10),
        /** A customer manager inviting to a company or sending an invitation again, together (S2-AC-20). */
        MEMBER_INVITATION(20),
        /**
         * A list asked with a search text: the issue lists, the account list, and the list of customers
         * staff can file for, together (S3-AC-15). A list without one is not counted.
         */
        SEARCH(60),
        /** An admin's test email (S4B-AC-26); each one opens an SMTP connection within the request. */
        TEST_MAIL(5),
        /**
         * Adding or editing a task, and adding or editing a task note, together (S6B-AC-13). Deleting is
         * not counted.
         */
        TASK(60),
        /**
         * Linking two issues and removing a link, together, also the requests refused with 400, 404, or 409
         * (S6C-AC-09).
         */
        LINK(60);

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
        count(action, accountId, 1);
    }

    /**
     * Counts {@code times} requests of {@code action} at once, all or none: when they do not all fit,
     * throws 429 with the time until enough of the window's requests have left it, and counts nothing.
     * More than the limit never fits; the caller refuses that before it counts.
     */
    public void count(Action action, long accountId, int times) {
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
            int over = window.size() + times - action.limit;
            if (over > 0) {
                // The request that has to leave the window last for these to fit; the newest when they never do.
                Instant last = window.stream().skip(Math.max(0, Math.min(over, window.size()) - 1L)).findFirst()
                        .orElse(now);
                throw ApiException.rateLimited(Duration.between(now, last.plus(WINDOW)));
            }
            for (int i = 0; i < times; i++) {
                window.addLast(now);
            }
        }
    }
}
