package io.popdesk.helpdesk.account;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Passwords are stored as Argon2id with the parameters of {@link PasswordHashes} (AUTH-5). */
@Configuration(proxyBeanMethods = false)
class PasswordConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordHashes.encoder();
    }
}
