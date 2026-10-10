package io.popdesk.helpdesk.account;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/**
 * How passwords are hashed (AUTH-5): Argon2id with 19 MiB of memory, 2 iterations, and 1 lane, the
 * least OWASP recommends, a 16-byte salt, and a 32-byte hash. The app and the
 * {@code popdesk admin reset-password} command, which runs without Spring, both take the encoder from
 * here, so the two cannot drift apart.
 *
 * <p>A hash names its own parameters, and matching reads them from the hash: a password stored with
 * other parameters still matches.
 */
public final class PasswordHashes {

    static final int SALT_BYTES = 16;
    static final int HASH_BYTES = 32;
    static final int PARALLELISM = 1;
    static final int MEMORY_KIB = 19_456;
    static final int ITERATIONS = 2;

    private PasswordHashes() {}

    public static Argon2PasswordEncoder encoder() {
        return new Argon2PasswordEncoder(SALT_BYTES, HASH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS);
    }
}
