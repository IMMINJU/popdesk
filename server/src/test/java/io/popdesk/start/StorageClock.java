package io.popdesk.start;

import io.popdesk.support.TestClock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Gives an app started with {@link Boot} a {@link TestClock} as its {@code Clock}, as the support
 * package does for a test context. It is no part of the app's component scan; a start adds it by
 * naming it in {@code spring.main.sources} ({@link StorageSteps#withTestClock}).
 */
@TestConfiguration(proxyBeanMethods = false)
class StorageClock {

    @Bean
    @Primary
    TestClock storageTestClock() {
        return new TestClock();
    }
}
