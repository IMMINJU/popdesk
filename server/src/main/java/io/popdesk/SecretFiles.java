package io.popdesk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Reads secrets given as files: {@code <NAME>_FILE} holds a path, and the file's content becomes the
 * value of {@code <NAME>} (docs/operate/configuration.md "Secrets", S0-AC-04). One line break at the
 * end ({@code \n} or {@code \r\n}) is cut; everything else stays. Setting both {@code <NAME>} and
 * {@code <NAME>_FILE} stops the start.
 *
 * <p>Runs on the {@code Environment} before any bean exists, so the database password is in place
 * before the {@code DataSource} is made.
 */
public class SecretFiles implements EnvironmentPostProcessor, Ordered {

    /** The secrets of configuration.md. Each may be given as {@code <NAME>_FILE}. */
    public static final List<String> SECRETS = List.of("POPDESK_DB_PASSWORD", "POPDESK_ADMIN_PASSWORD",
            "SMTP_PASSWORD", "IMAP_PASSWORD", "NOTIFY_WEBHOOK_URL", "S3_SECRET_KEY", "OIDC_CLIENT_SECRET");

    static final String SOURCE_NAME = "popdeskSecretFiles";

    /** A setting that cannot be used. The message names keys only, never a value. */
    public static class SecretFileException extends IllegalStateException {

        SecretFileException(String message) {
            super(message);
        }
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String name : SECRETS) {
            String fileKey = name + "_FILE";
            String path = environment.getProperty(fileKey);
            if (path == null || path.isEmpty()) {
                continue;
            }
            if (environment.containsProperty(name)) {
                throw new SecretFileException("Both " + name + " and " + fileKey + " are set. Set only one of them.");
            }
            values.put(name, read(fileKey, path));
        }
        if (!values.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, values));
        }
    }

    private static String read(String fileKey, String path) {
        String content;
        try {
            content = Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SecretFileException("Could not read the file that " + fileKey + " names ("
                    + e.getClass().getSimpleName() + ").");
        }
        return cutOneLineBreak(content);
    }

    /** Removes one {@code \r\n} or {@code \n} at the end, and nothing else. */
    public static String cutOneLineBreak(String content) {
        if (content.endsWith("\r\n")) {
            return content.substring(0, content.length() - 2);
        }
        if (content.endsWith("\n")) {
            return content.substring(0, content.length() - 1);
        }
        return content;
    }

    /** Before the config data processor reads application.yml, which refers to these keys. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }
}
