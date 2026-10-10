package io.popdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.helpdesk.account.PasswordHashes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Passwords stored before the Argon2 parameters were raised (verify.md V-15) still sign in: a hash
 * names its own parameters, and the encoder matches with those, not with its own.
 */
class EarlierPasswordHashTest {

    private static final String PASSWORD = "Earlier-hash-password-1";

    @Test
    @DisplayName("V-15 a password hashed with the earlier parameters (m=16384) still matches")
    void earlierHashStillMatches() {
        String earlier = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(PASSWORD);
        PasswordEncoder encoder = PasswordHashes.encoder();

        assertThat(earlier).startsWith("$argon2id$v=19$m=16384,t=2,p=1$");
        assertThat(encoder.matches(PASSWORD, earlier)).as("the password against its earlier hash").isTrue();
        assertThat(encoder.matches(PASSWORD + "x", earlier)).as("another password against that hash").isFalse();
    }

    @Test
    @DisplayName("V-15 a new hash has a 16-byte salt and a 32-byte hash")
    void newHashHasSaltAndHashLengths() {
        String[] parts = PasswordHashes.encoder().encode(PASSWORD).split("\\$");

        assertThat(parts).hasSize(6);
        assertThat(parts[3]).isEqualTo("m=19456,t=2,p=1");
        // Base64 without padding: 16 bytes are 22 characters, 32 bytes are 43.
        assertThat(parts[4]).hasSize(22);
        assertThat(parts[5]).hasSize(43);
    }
}
