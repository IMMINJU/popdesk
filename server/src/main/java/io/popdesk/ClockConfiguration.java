package io.popdesk;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one source of the current time (S0-AC-12, TIME-1). Code reads the time from an injected
 * {@code Clock}, never from {@code Instant.now()} or the database's {@code now()}.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
