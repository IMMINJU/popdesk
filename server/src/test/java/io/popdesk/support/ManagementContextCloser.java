package io.popdesk.support;

import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ContextClosedEvent;

/**
 * Closes the management context (the app's second port, {@code management.server.port}) when the app's
 * context closes. Spring Boot 4.1 only stops it then. The stopped management context keeps its Tomcat
 * server and that server's await thread, and the thread holds the closed app context with its pool and
 * Tomcat: after about fourteen contexts the 512 MB test JVM ran out of heap (10a verify.md).
 *
 * <p>Listed in {@code META-INF/spring/...ManagementContextConfiguration.imports}, so Spring Boot adds it
 * to every management context of the test JVM.
 */
@ManagementContextConfiguration(proxyBeanMethods = false)
class ManagementContextCloser {

    @Bean
    static CloseWithApp closeWithApp() {
        return new CloseWithApp();
    }

    static final class CloseWithApp implements ApplicationContextAware {

        @Override
        public void setApplicationContext(ApplicationContext context) {
            if (context instanceof ConfigurableApplicationContext management
                    && context.getParent() instanceof ConfigurableApplicationContext app) {
                // A lambda's event type cannot be read, so it gets every event and picks the app's close.
                app.addApplicationListener((ApplicationListener<ApplicationEvent>) event -> {
                    if (event instanceof ContextClosedEvent closed && closed.getApplicationContext() == app) {
                        management.close();
                    }
                });
            }
        }
    }
}
