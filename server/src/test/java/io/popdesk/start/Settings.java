package io.popdesk.start;

import io.popdesk.support.AppTest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The setting keys an app started by this package gets, as an installation would set them
 * (docs/operate/configuration.md). Starts with a working first install on a database; tests change
 * or remove the keys they are about.
 */
final class Settings {

    static final String ADMIN_EMAIL = "first-admin@start.popdesk.test";
    static final String ADMIN_PASSWORD = "First-Admin-Password-1";

    /** The app's own migrations (application.yml). Fixture migrations are added after them. */
    static final String APP_MIGRATIONS = "classpath:db/migration";

    private final Map<String, Object> values = new LinkedHashMap<>();

    private Settings() {}

    /** A first install on {@code db}: hosts, public address, first admin, and free ports. */
    static Settings firstInstall(Database db) {
        return new Settings()
                .set("POPDESK_DB_URL", db.appUrl())
                .set("POPDESK_DB_USER", db.user())
                .set("POPDESK_DB_PASSWORD", db.password())
                .set("POPDESK_PORTAL_HOST", AppTest.PORTAL_HOST)
                .set("POPDESK_CONSOLE_HOST", AppTest.CONSOLE_HOST)
                .set("POPDESK_PUBLIC_SCHEME", "http")
                .set("POPDESK_PUBLIC_PORT", "80")
                .set("POPDESK_ADMIN_EMAIL", ADMIN_EMAIL)
                .set("POPDESK_ADMIN_PASSWORD", ADMIN_PASSWORD)
                // Free ports instead of 8080 and 8081, as Spring Boot's own tests do.
                .set("server.port", "0")
                .set("management.server.port", "0");
    }

    Settings set(String key, Object value) {
        values.put(key, value);
        return this;
    }

    Settings without(String key) {
        values.remove(key);
        return this;
    }

    /** Adds a folder of fixture migrations under {@code src/test/resources/start/} after the app's own. */
    Settings withFixtureMigrations(String folder) {
        return set("spring.flyway.locations", APP_MIGRATIONS + ",classpath:start/" + folder);
    }

    Map<String, Object> asMap() {
        return new LinkedHashMap<>(values);
    }

    Object get(String key) {
        return values.get(key);
    }
}
