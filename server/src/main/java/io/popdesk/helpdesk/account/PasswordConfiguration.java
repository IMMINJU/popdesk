package io.popdesk.helpdesk.account;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Passwords are stored as Argon2id with Spring Security's defaults (AUTH-5). */
@Configuration(proxyBeanMethods = false)
class PasswordConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }
}
