package io.popdesk.support;

import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Beans every {@link AppTest} context adds to the app. The {@link TestClock} is {@code @Primary}, so
 * it wins over the app's own {@code Clock} bean wherever the app injects a {@code Clock}.
 */
@TestConfiguration(proxyBeanMethods = false)
class SupportConfiguration {

    @Bean
    @Primary
    TestClock testClock() {
        return new TestClock();
    }

    @Bean
    DbInspector dbInspector(PostgreSQLContainer postgres, DataSource dataSource) {
        return new DbInspector(postgres, dataSource);
    }
}
