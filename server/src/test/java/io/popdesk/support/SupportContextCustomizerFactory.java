package io.popdesk.support;

import java.util.List;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Runs before any Spring test context starts (registered in {@code META-INF/spring.factories}):
 * starts capturing standard output, allows the {@code Host} header in the JDK HTTP client, and gives
 * each context an {@link AppLog} that starts where that context's startup log starts.
 */
public class SupportContextCustomizerFactory implements ContextCustomizerFactory {

    public SupportContextCustomizerFactory() {
        ConsoleCapture.install();
        JdkHttp.allowHostHeader();
    }

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass,
            List<ContextConfigurationAttributes> configAttributes) {
        return new LogCustomizer();
    }

    private static final class LogCustomizer implements ContextCustomizer {

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration config) {
            context.getBeanFactory().registerSingleton("popdeskAppLog", new AppLog(ConsoleCapture.position()));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof LogCustomizer;
        }

        @Override
        public int hashCode() {
            return LogCustomizer.class.hashCode();
        }
    }
}
