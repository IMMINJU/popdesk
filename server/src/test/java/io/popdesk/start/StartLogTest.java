package io.popdesk.start;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The startup log of one installation with values a test can find: its own hosts, an https public
 * address, one trusted proxy, mail and webhook on, secrets with unique values, and one unknown key
 * under every prefix the app knows.
 */
class StartLogTest extends OwnStartTest {

    static final String PORTAL_HOST = "portal.summary.popdesk.test";
    static final String CONSOLE_HOST = "console.summary.popdesk.test";
    static final String TRUSTED_PROXY = "192.0.2.44/32";
    static final String SMTP_PASSWORD = "Smtp-Secret-7c41";
    static final String WEBHOOK_SECRET = "hook-secret-5d2e";

    /** One unknown key under each prefix the app knows (S0-AC-06). */
    static final List<String> UNKNOWN_KEYS = List.of("POPDESK_ADMIN_EMIAL", "SMTP_HOSTNAME", "IMAP_HOSTT",
            "S3_BUKET", "OIDC_ISSUR", "AUDIT_FORWARD_ULR");

    /** An unknown key under a prefix the app does not own. */
    static final String FOREIGN_KEY = "FOO_POPDESK_SETTING";

    static Database db;
    static Boot app;

    @BeforeAll
    static void startOnce() {
        db = postgres.newDatabase();
        Settings settings = Settings.firstInstall(db)
                .set("POPDESK_PORTAL_HOST", PORTAL_HOST)
                .set("POPDESK_CONSOLE_HOST", CONSOLE_HOST)
                .set("POPDESK_PUBLIC_SCHEME", "https")
                .set("POPDESK_PUBLIC_PORT", "8443")
                .set("POPDESK_TRUSTED_PROXIES", TRUSTED_PROXY)
                .set("SMTP_HOST", "127.0.0.1")
                .set("SMTP_PORT", "2525")
                .set("SMTP_USER", "popdesk")
                .set("SMTP_PASSWORD", SMTP_PASSWORD)
                .set("MAIL_FROM", "popdesk@summary.popdesk.test")
                .set("NOTIFY_WEBHOOK_URL", "https://127.0.0.1:9/" + WEBHOOK_SECRET)
                .set(FOREIGN_KEY, "1");
        for (String key : UNKNOWN_KEYS) {
            settings.set(key, "1");
        }
        app = Boot.start(settings);
    }

    @AfterAll
    static void closeApp() {
        if (app != null) {
            app.close();
        }
    }

    @Test
    @DisplayName("S0-AC-06 one startup line names both hosts, the public address, trusted proxies, and what is on, without secrets")
    void summaryLine() {
        app.assertStarted();

        List<String> lines = app.logLines().stream()
                .filter(line -> line.contains(PORTAL_HOST) && line.contains(CONSOLE_HOST))
                .toList();
        assertThat(lines).as("lines naming both hosts").isNotEmpty();
        assertThat(lines).as("one line with hosts, public address, trusted proxies, mail, webhook, storage")
                .anyMatch(line -> line.contains("https") && line.contains("8443") && line.contains(TRUSTED_PROXY)
                        && lower(line).contains("mail") && lower(line).contains("webhook")
                        && lower(line).contains("storage"));

        assertThat(app.log()).as("the startup log")
                .doesNotContain(Settings.ADMIN_PASSWORD)
                .doesNotContain(SMTP_PASSWORD)
                .doesNotContain(db.password())
                .doesNotContain(WEBHOOK_SECRET);
    }

    @Test
    @DisplayName("S0-AC-06 every unknown key under a known prefix gets a warning; other prefixes and known keys do not")
    void unknownKeyWarnings() {
        app.assertStarted();
        List<String> lines = app.logLines();

        for (String key : UNKNOWN_KEYS) {
            assertThat(lines).as("a WARN line naming %s", key)
                    .anyMatch(line -> line.contains("WARN") && line.contains(key));
        }
        assertThat(lines).as("lines naming %s", FOREIGN_KEY).noneMatch(line -> line.contains(FOREIGN_KEY));
        assertThat(lines).as("WARN lines naming a known key")
                .noneMatch(line -> line.contains("WARN") && (line.contains("POPDESK_TRUSTED_PROXIES")
                        || line.contains("POPDESK_PORTAL_HOST") || line.contains("SMTP_USER")));
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
