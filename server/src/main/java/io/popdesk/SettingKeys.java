package io.popdesk;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The setting keys popdesk knows (docs/operate/configuration.md). A key with one of the
 * {@link #PREFIXES} that is not listed here gets a warning at startup (S0-AC-06), so add every new key
 * here and to configuration.md together.
 */
public final class SettingKeys {

    /** The prefixes popdesk owns. Keys with other prefixes are none of its business. */
    public static final List<String> PREFIXES = List.of("POPDESK_", "SMTP_", "IMAP_", "S3_", "OIDC_", "AUDIT_");

    static final Pattern OWNED = Pattern.compile("(POPDESK|SMTP|IMAP|S3|OIDC|AUDIT)_[A-Z0-9_]*");

    private static final List<String> KEYS = List.of(
            "POPDESK_DB_URL", "POPDESK_DB_USER", "POPDESK_DB_PASSWORD",
            "POPDESK_PORTAL_HOST", "POPDESK_CONSOLE_HOST", "POPDESK_PUBLIC_SCHEME", "POPDESK_PUBLIC_PORT",
            "POPDESK_ADMIN_EMAIL", "POPDESK_ADMIN_PASSWORD",
            "POPDESK_TIMEZONE", "POPDESK_LOCALE",
            "SMTP_HOST", "SMTP_PORT", "SMTP_USER", "SMTP_PASSWORD", "SMTP_ALLOW_PLAINTEXT", "MAIL_FROM",
            "NOTIFY_WEBHOOK_URL",
            "POPDESK_ATTACHMENT_QUOTA", "POPDESK_STORAGE", "POPDESK_STORAGE_DIR",
            "S3_ENDPOINT", "S3_REGION", "S3_BUCKET", "S3_ACCESS_KEY", "S3_SECRET_KEY",
            "POPDESK_AUDIT_RETENTION", "POPDESK_ISSUE_RETENTION", "AUDIT_FORWARD_URL",
            "IMAP_HOST", "IMAP_PORT", "IMAP_USER", "IMAP_PASSWORD", "IMAP_FOLDER", "IMAP_ALLOW_PLAINTEXT",
            "IMAP_PROCESSED_FOLDER", "IMAP_QUARANTINE_FOLDER", "MAIL_INBOUND_ADDRESS",
            "OIDC_ISSUER", "OIDC_CLIENT_ID", "OIDC_CLIENT_SECRET", "OIDC_APPS",
            "POPDESK_TRUSTED_PROXIES", "POPDESK_INSTANCE_LOCK_WAIT",
            "POPDESK_SANDBOX", "POPDESK_SANDBOX_MAIL_TO");

    private static final Set<String> KNOWN = known();

    private SettingKeys() {}

    private static Set<String> known() {
        Set<String> out = new HashSet<>(KEYS);
        for (String secret : SecretFiles.SECRETS) {
            out.add(secret + "_FILE");
        }
        return Set.copyOf(out);
    }

    /** Whether {@code key} has a prefix popdesk owns but is not a key it knows. */
    static boolean unknown(String key) {
        return OWNED.matcher(key).matches() && !KNOWN.contains(key);
    }
}
