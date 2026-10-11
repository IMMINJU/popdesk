package io.popdesk.helpdesk.account;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Lets only {@link #AT_ONCE} password hashes be computed at a time, hashing and matching counted
 * together. One Argon2id computation holds 19 MiB ({@link PasswordHashes}), and sign-in computes one
 * for anyone who asks, so without a limit requests sent together could fill the heap.
 *
 * <p>A computation over the limit is not refused: it waits its turn, in the order of arrival. A
 * sign-in that is a little late is better than one that fails.
 */
final class LimitedPasswordEncoder implements PasswordEncoder {

    /**
     * A constant and not a setting: 4 computations hold 76 MiB (4 × 19), which the image's heap of
     * 384 MB takes beside everything else. More at once would not be faster on the few cores a small
     * host has.
     */
    static final int AT_ONCE = 4;

    private final PasswordEncoder encoder;
    private final Semaphore turns = new Semaphore(AT_ONCE, true);

    LimitedPasswordEncoder(PasswordEncoder encoder) {
        this.encoder = encoder;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return inTurn(() -> encoder.encode(rawPassword));
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return inTurn(() -> encoder.matches(rawPassword, encodedPassword));
    }

    /** Reads the parameters a hash names and computes nothing, so it takes no turn. */
    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return encoder.upgradeEncoding(encodedPassword);
    }

    private <T> T inTurn(Supplier<T> computation) {
        try {
            turns.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to compute a password hash.", e);
        }
        try {
            return computation.get();
        } finally {
            turns.release();
        }
    }
}
