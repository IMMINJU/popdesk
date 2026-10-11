package io.popdesk.helpdesk.account;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Passwords are stored as Argon2id with the parameters of {@link PasswordHashes} (AUTH-5). Every part of
 * the app hashes and matches through this one bean, so at most {@link LimitedPasswordEncoder#AT_ONCE}
 * computations run at a time.
 */
@Configuration(proxyBeanMethods = false)
class PasswordConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new LimitedPasswordEncoder(PasswordHashes.encoder());
    }
}
