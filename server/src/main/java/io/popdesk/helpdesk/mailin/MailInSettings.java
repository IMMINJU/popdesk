package io.popdesk.helpdesk.mailin;

import io.popdesk.notification.MailInState.OffReason;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The mail-in settings (MAILIN, S12-AC-01 to S12-AC-03): {@code IMAP_HOST}, {@code IMAP_PORT}, {@code IMAP_USER},
 * {@code IMAP_PASSWORD} (also as {@code IMAP_PASSWORD_FILE}, which {@code SecretFiles} has turned into the value),
 * {@code IMAP_ALLOW_PLAINTEXT}, the three folders, and {@code MAIL_INBOUND_ADDRESS}. Reading them is pure: the values
 * and whether this is a sandbox in, whether mail-in is on and how to reach the server, or the problems, out. No problem
 * holds a value, so none holds the password.
 */
final class MailInSettings {

    public static final String HOST = "IMAP_HOST";
    public static final String PORT = "IMAP_PORT";
    public static final String USER = "IMAP_USER";
    public static final String PASSWORD = "IMAP_PASSWORD";
    public static final String FOLDER = "IMAP_FOLDER";
    public static final String PROCESSED_FOLDER = "IMAP_PROCESSED_FOLDER";
    public static final String QUARANTINE_FOLDER = "IMAP_QUARANTINE_FOLDER";
    public static final String ALLOW_PLAINTEXT = "IMAP_ALLOW_PLAINTEXT";
    public static final String INBOUND_ADDRESS = "MAIL_INBOUND_ADDRESS";

    /** The four keys that turn mail-in on together (acceptance.md 2절, 5절 3). */
    static final List<String> REQUIRED = List.of(HOST, USER, PASSWORD, INBOUND_ADDRESS);

    /** TLS from the first byte (D-51); any other port must switch to TLS with STARTTLS. */
    public static final int IMPLICIT_TLS_PORT = 993;

    static final String DEFAULT_FOLDER = "INBOX";
    static final String DEFAULT_PROCESSED_FOLDER = "popdesk-processed";
    static final String DEFAULT_QUARANTINE_FOLDER = "popdesk-quarantine";

    static final String CONNECT_TIMEOUT_MILLIS = "10000";
    static final String TIMEOUT_MILLIS = "30000";
    static final String FETCH_SIZE_BYTES = "1048576";

    /** One address: something on each side of one {@code @}, and nothing that could start a second address. */
    private static final Pattern ADDRESS = Pattern.compile("[^\\s@,;:<>()\"\\\\]+@[^\\s@,;:<>()\"\\\\]+");

    /** Digits only; the leading zeros are not counted in the five. */
    private static final Pattern WHOLE_NUMBER = Pattern.compile("0*([0-9]{1,5})");

    private final List<String> problems;
    private final OffReason offReason;
    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final List<String> folders;
    private final boolean plainTextAllowed;
    private final String inboundAddress;

    private MailInSettings(List<String> problems, OffReason offReason, String host, int port, String user,
            String password, List<String> folders, boolean plainTextAllowed, String inboundAddress) {
        this.problems = List.copyOf(problems);
        this.offReason = offReason;
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.folders = List.copyOf(folders);
        this.plainTextAllowed = plainTextAllowed;
        this.inboundAddress = inboundAddress;
    }

    /**
     * Reads the keys. A key that is not set and one set to blank text are the same, except a folder: a folder key set
     * to blank text is a problem, one not set is its default.
     *
     * <ul>
     *   <li>None of {@code IMAP_HOST}, {@code IMAP_USER}, {@code IMAP_PASSWORD}, and {@code MAIL_INBOUND_ADDRESS}: off
     *       with {@link OffReason#NOT_SET} and no problem, in a sandbox too; the other keys are not looked at.</li>
     *   <li>Some of them but not all: one problem per missing key, naming it.</li>
     *   <li>{@code IMAP_PORT}, when set: digits only, from 1 to 65535; else a problem. Not set: 993.</li>
     *   <li>{@code MAIL_INBOUND_ADDRESS}: one address, something on each side of one {@code @}, without a {@code +}
     *       (the {@code +code} is the customer's to add); else a problem. Kept with the spaces around it cut.</li>
     *   <li>{@code IMAP_FOLDER} ({@code INBOX}), {@code IMAP_PROCESSED_FOLDER} ({@code popdesk-processed}), and
     *       {@code IMAP_QUARANTINE_FOLDER} ({@code popdesk-quarantine}): a blank one is a problem, and so is the
     *       second of two that are the same, in any letter case, naming the later key.</li>
     *   <li>{@code IMAP_ALLOW_PLAINTEXT}: {@code true} in any letter case and with spaces around it; anything else is
     *       {@code false}.</li>
     *   <li>All four set and no problem: on, unless {@code sandbox}, then off with {@link OffReason#SANDBOX}.</li>
     * </ul>
     *
     * @param setting the value of a key, or null when it is not set; called with the keys of this class only
     * @param sandbox whether {@code POPDESK_SANDBOX=true}
     * @return the settings; {@link #problems} is empty when the app may start with them
     */
    public static MailInSettings read(Function<String, String> setting, boolean sandbox) {
        List<String> missing = new ArrayList<>();
        for (String key : REQUIRED) {
            if (value(setting, key) == null) {
                missing.add(key);
            }
        }
        if (missing.size() == REQUIRED.size()) {
            return off(List.of(), OffReason.NOT_SET);
        }
        List<String> problems = new ArrayList<>();
        for (String key : missing) {
            problems.add(key + " is not set, while other mail-in keys are. Set " + HOST + ", " + USER + ", " + PASSWORD
                    + " (or " + PASSWORD + "_FILE), and " + INBOUND_ADDRESS + " together, or none of them.");
        }

        int port = IMPLICIT_TLS_PORT;
        String portValue = value(setting, PORT);
        if (portValue != null) {
            Matcher digits = WHOLE_NUMBER.matcher(portValue);
            port = digits.matches() ? Integer.parseInt(digits.group(1)) : 0;
            if (port < 1 || port > 65535) {
                problems.add(PORT + " must be a whole number from 1 to 65535.");
            }
        }

        String address = value(setting, INBOUND_ADDRESS);
        if (address != null && (!ADDRESS.matcher(address).matches() || address.contains("+"))) {
            problems.add(INBOUND_ADDRESS + " must be one email address without a +. Customers add +CODE to it "
                    + "to choose a company.");
        }

        List<String> folders = new ArrayList<>();
        List<String> folderKeys = List.of(FOLDER, PROCESSED_FOLDER, QUARANTINE_FOLDER);
        List<String> defaults = List.of(DEFAULT_FOLDER, DEFAULT_PROCESSED_FOLDER, DEFAULT_QUARANTINE_FOLDER);
        for (int i = 0; i < folderKeys.size(); i++) {
            String key = folderKeys.get(i);
            String raw = setting.apply(key);
            String folder = raw == null ? defaults.get(i) : raw.strip();
            if (folder.isEmpty()) {
                problems.add(key + " is empty. Leave it out for its default, or name a folder.");
            } else if (folders.stream().anyMatch(f -> f.equalsIgnoreCase(folder))) {
                problems.add(key + " names the same folder as another of " + String.join(", ", folderKeys)
                        + ". The three must be different folders.");
            }
            folders.add(folder);
        }

        if (!problems.isEmpty()) {
            return off(problems, OffReason.NOT_SET);
        }
        String rawPassword = setting.apply(PASSWORD);
        String plain = setting.apply(ALLOW_PLAINTEXT);
        return new MailInSettings(List.of(), sandbox ? OffReason.SANDBOX : null, value(setting, HOST), port,
                value(setting, USER), rawPassword, folders, plain != null && plain.strip().equalsIgnoreCase("true"),
                address);
    }

    private static MailInSettings off(List<String> problems, OffReason reason) {
        return new MailInSettings(problems, reason, null, 0, null, null, List.of(), false, null);
    }

    private static String value(Function<String, String> setting, String key) {
        String value = setting.apply(key);
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** What stops the start, each naming its key and never a value; empty when nothing does. */
    public List<String> problems() {
        return problems;
    }

    /** Whether rounds read the mailbox: the four keys are set, nothing is wrong, and this is no sandbox. */
    public boolean enabled() {
        return problems.isEmpty() && offReason == null;
    }

    /** Why mail-in is off; null when on. {@link OffReason#NOT_SET} also with problems. */
    public OffReason offReason() {
        return offReason;
    }

    /** {@code IMAP_HOST}; null without the settings. */
    public String host() {
        return host;
    }

    /** {@code IMAP_PORT}, 993 when not set; 0 without the settings. */
    public int port() {
        return port;
    }

    /** {@code IMAP_USER}; null without the settings. Never logged. */
    public String user() {
        return user;
    }

    /** {@code IMAP_PASSWORD} as it is, spaces included; null without the settings. Never logged. */
    public String password() {
        return password;
    }

    /** {@code IMAP_FOLDER}, the folder rounds read; null without the settings. */
    public String folder() {
        return folders.isEmpty() ? null : folders.get(0);
    }

    /** {@code IMAP_PROCESSED_FOLDER}; null without the settings. */
    public String processedFolder() {
        return folders.isEmpty() ? null : folders.get(1);
    }

    /** {@code IMAP_QUARANTINE_FOLDER}; null without the settings. */
    public String quarantineFolder() {
        return folders.isEmpty() ? null : folders.get(2);
    }

    /** Whether {@code IMAP_ALLOW_PLAINTEXT=true}; false without the settings. */
    public boolean plainTextAllowed() {
        return plainTextAllowed;
    }

    /**
     * Whether a connection may go without encryption: plain text allowed on a port other than 993. The start warns
     * then (D-51, S12-AC-02).
     */
    public boolean plainTextPossible() {
        return plainTextAllowed && port != IMPLICIT_TLS_PORT;
    }

    /**
     * {@code MAIL_INBOUND_ADDRESS}, set in a sandbox too; null without the settings. Replies go to it only while
     * mail-in is on ({@link #enabled}).
     */
    public String inboundAddress() {
        return inboundAddress;
    }

    /** The part after the {@code @} of {@link #inboundAddress}, in lower case; null without the settings. */
    public String inboundDomain() {
        return inboundAddress == null ? null
                : inboundAddress.substring(inboundAddress.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * The mail library's properties of a connection to the IMAP server (design.md 0절 14), the password not among
     * them. Protocol {@code imap} always, so the timeouts under {@code mail.imap.*} are read on port 993 too: there
     * with {@code mail.imap.ssl.enable}, on any other port with STARTTLS, required unless plain text is allowed. The
     * server's certificate and host name are checked against the JVM's trust store, and a message read is not marked
     * seen ({@code peek}).
     *
     * @throws IllegalStateException without the settings
     */
    public Properties imapProperties() {
        if (host == null) {
            throw new IllegalStateException("Mail-in is not set up.");
        }
        Properties properties = new Properties();
        properties.setProperty("mail.store.protocol", "imap");
        properties.setProperty("mail.imap.host", host);
        properties.setProperty("mail.imap.port", Integer.toString(port));
        properties.setProperty("mail.imap.connectiontimeout", CONNECT_TIMEOUT_MILLIS);
        properties.setProperty("mail.imap.timeout", TIMEOUT_MILLIS);
        properties.setProperty("mail.imap.writetimeout", TIMEOUT_MILLIS);
        properties.setProperty("mail.imap.ssl.checkserveridentity", "true");
        properties.setProperty("mail.imap.peek", "true");
        // A mail is read in parts of 1 MB, not the library's 16 KB: a mail near the size limit took
        // hundreds of requests and over 15 seconds.
        properties.setProperty("mail.imap.fetchsize", FETCH_SIZE_BYTES);
        properties.setProperty("mail.debug", "false");
        if (port == IMPLICIT_TLS_PORT) {
            properties.setProperty("mail.imap.ssl.enable", "true");
        } else {
            properties.setProperty("mail.imap.starttls.enable", "true");
            properties.setProperty("mail.imap.starttls.required", Boolean.toString(!plainTextAllowed));
        }
        return properties;
    }

    @Override
    public String toString() {
        return "MailInSettings[enabled=" + enabled() + ", offReason=" + offReason + ", problems=" + problems.size()
                + "]";
    }
}
