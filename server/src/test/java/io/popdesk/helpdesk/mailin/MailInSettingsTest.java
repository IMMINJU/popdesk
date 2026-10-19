package io.popdesk.helpdesk.mailin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.popdesk.notification.MailInState.OffReason;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading the mail-in settings (S12-AC-01 to S12-AC-03; D-51, D-46; acceptance.md 5절 3, 4; design.md 0절 14, 15).
 * Written from the Javadoc of {@link MailInSettings} and the slice documents alone.
 */
class MailInSettingsTest {

    private static final String HOST = "imap.example.com";
    private static final String USER = "support-box";
    private static final String PASSWORD = "s3cret-Pa55";
    private static final String INBOUND = "support@example.com";

    private static final Set<String> KEYS = Set.of("IMAP_HOST", "IMAP_PORT", "IMAP_USER", "IMAP_PASSWORD",
            "IMAP_FOLDER", "IMAP_PROCESSED_FOLDER", "IMAP_QUARANTINE_FOLDER", "IMAP_ALLOW_PLAINTEXT",
            "MAIL_INBOUND_ADDRESS");

    /** The four keys that turn mail-in on, set; more pairs of key and value added or replaced (null unsets). */
    private static Map<String, String> full(String... keyValue) {
        Map<String, String> map = new HashMap<>();
        map.put("IMAP_HOST", HOST);
        map.put("IMAP_USER", USER);
        map.put("IMAP_PASSWORD", PASSWORD);
        map.put("MAIL_INBOUND_ADDRESS", INBOUND);
        return with(map, keyValue);
    }

    private static Map<String, String> with(Map<String, String> map, String... keyValue) {
        for (int i = 0; i < keyValue.length; i += 2) {
            map.put(keyValue[i], keyValue[i + 1]);
        }
        return map;
    }

    private static MailInSettings read(Map<String, String> map) {
        return MailInSettings.read(map::get, false);
    }

    private static String onlyProblem(MailInSettings settings) {
        assertThat(settings.problems()).hasSize(1);
        return settings.problems().get(0);
    }

    // ---- none of the four (acceptance.md 5절 3, design.md 0절 15 \u2462) ----

    @Test
    @DisplayName("S12-AC-01: none of the four keys is off with NOT_SET and no problem, in a sandbox too")
    void s12Ac01_noneOfTheFourIsOff() {
        for (boolean sandbox : new boolean[] {false, true}) {
            MailInSettings settings = MailInSettings.read(key -> null, sandbox);
            assertThat(settings.problems()).isEmpty();
            assertThat(settings.enabled()).isFalse();
            assertThat(settings.offReason()).isEqualTo(OffReason.NOT_SET);
        }
    }

    @Test
    @DisplayName("S12-AC-01: blank values are not set")
    void s12Ac01_blankIsNotSet() {
        Map<String, String> map = new HashMap<>(Map.of("IMAP_HOST", "", "IMAP_USER", "  ", "IMAP_PASSWORD", "\t",
                "MAIL_INBOUND_ADDRESS", " \n "));
        MailInSettings settings = read(map);
        assertThat(settings.problems()).isEmpty();
        assertThat(settings.offReason()).isEqualTo(OffReason.NOT_SET);
    }

    @Test
    @DisplayName("S12-AC-01: without the four keys the other keys are not looked at")
    void s12Ac01_otherKeysNotLookedAt() {
        Map<String, String> map = new HashMap<>(Map.of("IMAP_PORT", "not a port", "IMAP_FOLDER", " ",
                "IMAP_PROCESSED_FOLDER", "INBOX", "IMAP_ALLOW_PLAINTEXT", "true"));
        List<String> asked = new ArrayList<>();
        MailInSettings settings = MailInSettings.read(key -> {
            asked.add(key);
            return map.get(key);
        }, false);
        assertThat(settings.problems()).isEmpty();
        assertThat(settings.offReason()).isEqualTo(OffReason.NOT_SET);
        assertThat(asked).doesNotContain("IMAP_PORT", "IMAP_FOLDER", "IMAP_PROCESSED_FOLDER",
                "IMAP_QUARANTINE_FOLDER", "IMAP_ALLOW_PLAINTEXT");
    }

    @Test
    @DisplayName("S12-AC-01: without the settings the values are null, 0 and false")
    void s12Ac01_valuesWithoutSettings() {
        MailInSettings settings = MailInSettings.read(key -> null, false);
        assertThat(settings.host()).isNull();
        assertThat(settings.port()).isZero();
        assertThat(settings.user()).isNull();
        assertThat(settings.password()).isNull();
        assertThat(settings.folder()).isNull();
        assertThat(settings.processedFolder()).isNull();
        assertThat(settings.quarantineFolder()).isNull();
        assertThat(settings.plainTextAllowed()).isFalse();
        assertThat(settings.plainTextPossible()).isFalse();
        assertThat(settings.inboundAddress()).isNull();
        assertThat(settings.inboundDomain()).isNull();
        assertThatThrownBy(settings::imapProperties).isInstanceOf(IllegalStateException.class);
    }

    // ---- some of the four ----

    @Test
    @DisplayName("S12-AC-01: each one of the four missing while the others are set is one problem naming it")
    void s12Ac01_oneMissingKeyIsOneProblem() {
        for (String key : MailInSettings.REQUIRED) {
            MailInSettings settings = read(full(key, null));
            assertThat(onlyProblem(settings)).as(key).startsWith(key);
            assertThat(settings.enabled()).isFalse();
            assertThat(settings.offReason()).isEqualTo(OffReason.NOT_SET);
        }
        assertThat(onlyProblem(read(full("IMAP_PASSWORD", "   ")))).startsWith("IMAP_PASSWORD");
    }

    @Test
    @DisplayName("S12-AC-01: three of the four missing are three problems, one naming each")
    void s12Ac01_threeMissingKeys() {
        MailInSettings settings = read(new HashMap<>(Map.of("IMAP_HOST", HOST)));
        assertThat(settings.problems()).hasSize(3);
        for (String key : List.of("IMAP_USER", "IMAP_PASSWORD", "MAIL_INBOUND_ADDRESS")) {
            assertThat(settings.problems()).as(key).anyMatch(p -> p.startsWith(key));
        }
        assertThat(settings.problems()).noneMatch(p -> p.startsWith("IMAP_HOST"));
        assertThat(settings.enabled()).isFalse();
    }

    @Test
    @DisplayName("S12-AC-01: the four keys are host, user, password and inbound address")
    void s12Ac01_requiredKeys() {
        assertThat(MailInSettings.REQUIRED)
                .containsExactlyInAnyOrder("IMAP_HOST", "IMAP_USER", "IMAP_PASSWORD", "MAIL_INBOUND_ADDRESS");
    }

    // ---- all set ----

    @Test
    @DisplayName("S12-AC-02: all four set and no problem is on, with the defaults")
    void s12Ac02_allSetIsOn() {
        MailInSettings settings = read(full());
        assertThat(settings.problems()).isEmpty();
        assertThat(settings.enabled()).isTrue();
        assertThat(settings.offReason()).isNull();
        assertThat(settings.host()).isEqualTo(HOST);
        assertThat(settings.port()).isEqualTo(993);
        assertThat(settings.user()).isEqualTo(USER);
        assertThat(settings.password()).isEqualTo(PASSWORD);
        assertThat(settings.folder()).isEqualTo("INBOX");
        assertThat(settings.processedFolder()).isEqualTo("popdesk-processed");
        assertThat(settings.quarantineFolder()).isEqualTo("popdesk-quarantine");
        assertThat(settings.plainTextAllowed()).isFalse();
        assertThat(settings.plainTextPossible()).isFalse();
        assertThat(settings.inboundAddress()).isEqualTo(INBOUND);
        assertThat(settings.inboundDomain()).isEqualTo("example.com");
    }

    @Test
    @DisplayName("S12-AC-02: the keys of this class only are asked for")
    void s12Ac02_onlyItsKeysAreAsked() {
        List<String> asked = new ArrayList<>();
        Map<String, String> map = full("IMAP_PORT", "143", "IMAP_ALLOW_PLAINTEXT", "true");
        MailInSettings.read(key -> {
            asked.add(key);
            return map.get(key);
        }, false);
        assertThat(KEYS).containsAll(asked);
    }

    @Test
    @DisplayName("S12-AC-02: the folders as set")
    void s12Ac02_foldersAsSet() {
        MailInSettings settings = read(full("IMAP_FOLDER", "Support", "IMAP_PROCESSED_FOLDER", "Support/Done",
                "IMAP_QUARANTINE_FOLDER", "Support/Held"));
        assertThat(settings.problems()).isEmpty();
        assertThat(settings.folder()).isEqualTo("Support");
        assertThat(settings.processedFolder()).isEqualTo("Support/Done");
        assertThat(settings.quarantineFolder()).isEqualTo("Support/Held");
    }

    @Test
    @DisplayName("S12-AC-02: the password is kept as it is, spaces included")
    void s12Ac02_passwordAsItIs() {
        assertThat(read(full("IMAP_PASSWORD", "  pass word  ")).password()).isEqualTo("  pass word  ");
    }

    @Test
    @DisplayName("S12-AC-01: the inbound address is kept with the spaces around it cut; its domain in lower case")
    void s12Ac01_inboundAddressTrimmed() {
        MailInSettings settings = read(full("MAIL_INBOUND_ADDRESS", "  Support@Example.COM  "));
        assertThat(settings.problems()).isEmpty();
        assertThat(settings.inboundAddress()).isEqualTo("Support@Example.COM");
        assertThat(settings.inboundDomain()).isEqualTo("example.com");
    }

    // ---- sandbox (S12-AC-03) ----

    @Test
    @DisplayName("S12-AC-03: in a sandbox the settings are off with SANDBOX, and the inbound address is still set")
    void s12Ac03_sandboxIsOff() {
        MailInSettings settings = MailInSettings.read(full()::get, true);
        assertThat(settings.problems()).isEmpty();
        assertThat(settings.enabled()).isFalse();
        assertThat(settings.offReason()).isEqualTo(OffReason.SANDBOX);
        assertThat(settings.inboundAddress()).isEqualTo(INBOUND);
    }

    @Test
    @DisplayName("S12-AC-01, 03: in a sandbox broken settings are still problems")
    void s12Ac03_sandboxStillChecks() {
        MailInSettings settings = MailInSettings.read(full("IMAP_USER", null)::get, true);
        assertThat(onlyProblem(settings)).startsWith("IMAP_USER");
        assertThat(settings.enabled()).isFalse();
    }

    // ---- port ----

    @Test
    @DisplayName("S12-AC-01: a port from 1 to 65535 is taken")
    void s12Ac01_validPorts() {
        for (String port : List.of("1", "143", "993", "65535", "00143")) {
            MailInSettings settings = read(full("IMAP_PORT", port));
            assertThat(settings.problems()).as(port).isEmpty();
            assertThat(settings.port()).as(port).isEqualTo(Integer.parseInt(port));
        }
    }

    @Test
    @DisplayName("S12-AC-01: a port written with more leading zeros is still digits only and in range")
    void s12Ac01_portWithManyLeadingZeros() {
        MailInSettings settings = read(full("IMAP_PORT", "000143"));
        assertThat(settings.problems()).isEmpty();
        assertThat(settings.port()).isEqualTo(143);
    }

    @Test
    @DisplayName("S12-AC-01: a port outside 1 to 65535, or not digits only, is a problem naming IMAP_PORT")
    void s12Ac01_invalidPorts() {
        for (String port : List.of("0", "65536", "99999", "-1", "+143", "1.5", "143a", "abc", "0x8F",
                "99999999999", "1 43")) {
            MailInSettings settings = read(full("IMAP_PORT", port));
            assertThat(onlyProblem(settings)).as(port).startsWith("IMAP_PORT");
            assertThat(settings.enabled()).isFalse();
        }
    }

    @Test
    @DisplayName("S12-AC-01: problems come together, a port and a missing key")
    void s12Ac01_problemsComeTogether() {
        MailInSettings settings = read(full("IMAP_USER", null, "IMAP_PORT", "0", "IMAP_FOLDER", " "));
        assertThat(settings.problems()).hasSize(3);
        assertThat(settings.problems()).anyMatch(p -> p.startsWith("IMAP_USER"));
        assertThat(settings.problems()).anyMatch(p -> p.startsWith("IMAP_PORT"));
        assertThat(settings.problems()).anyMatch(p -> p.startsWith("IMAP_FOLDER"));
    }

    // ---- inbound address ----

    @Test
    @DisplayName("S12-AC-01: an inbound address that is not one address, or holds a +, is a problem")
    void s12Ac01_invalidInboundAddress() {
        for (String address : List.of("support+x@example.com", "+@example.com", "support", "@example.com",
                "support@", "a@b@example.com", "a@example.com, b@example.com", "a@example.com;b@example.com",
                "Support <support@example.com>", "<support@example.com>", "a@example.com b@example.com")) {
            MailInSettings settings = read(full("MAIL_INBOUND_ADDRESS", address));
            assertThat(onlyProblem(settings)).as(address).startsWith("MAIL_INBOUND_ADDRESS");
            assertThat(settings.enabled()).as(address).isFalse();
        }
    }

    @Test
    @DisplayName("S12-AC-01: an inbound address of one address is taken")
    void s12Ac01_validInboundAddress() {
        for (String address : List.of("support@example.com", "a@b", "first.last-x_y@mail.example.co.kr")) {
            assertThat(read(full("MAIL_INBOUND_ADDRESS", address)).problems()).as(address).isEmpty();
        }
    }

    // ---- folders ----

    @Test
    @DisplayName("S12-AC-01: a folder set to blank text is a problem naming it")
    void s12Ac01_blankFolder() {
        for (String key : List.of("IMAP_FOLDER", "IMAP_PROCESSED_FOLDER", "IMAP_QUARANTINE_FOLDER")) {
            for (String blank : List.of("", "   ")) {
                MailInSettings settings = read(full(key, blank));
                assertThat(onlyProblem(settings)).as(key).startsWith(key);
            }
        }
    }

    @Test
    @DisplayName("S12-AC-01: the second of two same folders, in any letter case, is a problem naming the later key")
    void s12Ac01_sameFolders() {
        assertThat(onlyProblem(read(full("IMAP_PROCESSED_FOLDER", "inbox")))).startsWith("IMAP_PROCESSED_FOLDER");
        assertThat(onlyProblem(read(full("IMAP_QUARANTINE_FOLDER", "Inbox")))).startsWith("IMAP_QUARANTINE_FOLDER");
        assertThat(onlyProblem(read(full("IMAP_QUARANTINE_FOLDER", "POPDESK-PROCESSED"))))
                .startsWith("IMAP_QUARANTINE_FOLDER");
        assertThat(onlyProblem(read(full("IMAP_FOLDER", "popdesk-quarantine", "IMAP_QUARANTINE_FOLDER", null))))
                .startsWith("IMAP_QUARANTINE_FOLDER");
        MailInSettings all = read(full("IMAP_FOLDER", "Box", "IMAP_PROCESSED_FOLDER", "box",
                "IMAP_QUARANTINE_FOLDER", "BOX"));
        assertThat(all.problems()).hasSize(2);
        assertThat(all.problems().get(0)).startsWith("IMAP_PROCESSED_FOLDER");
        assertThat(all.problems().get(1)).startsWith("IMAP_QUARANTINE_FOLDER");
    }

    // ---- plain text (D-51) ----

    @Test
    @DisplayName("S12-AC-02: IMAP_ALLOW_PLAINTEXT is true in any letter case and with spaces, anything else false")
    void s12Ac02_allowPlaintext() {
        for (String value : List.of("true", "TRUE", "True", "  true  ")) {
            assertThat(read(full("IMAP_ALLOW_PLAINTEXT", value)).plainTextAllowed()).as(value).isTrue();
        }
        for (String value : List.of("false", "yes", "1", "on", "", "truee", "t")) {
            assertThat(read(full("IMAP_ALLOW_PLAINTEXT", value)).plainTextAllowed()).as(value).isFalse();
        }
        assertThat(read(full()).plainTextAllowed()).isFalse();
    }

    @Test
    @DisplayName("S12-AC-02: plain text is possible only when allowed and the port is not 993")
    void s12Ac02_plainTextPossible() {
        assertThat(read(full("IMAP_ALLOW_PLAINTEXT", "true", "IMAP_PORT", "143")).plainTextPossible()).isTrue();
        assertThat(read(full("IMAP_ALLOW_PLAINTEXT", "true", "IMAP_PORT", "993")).plainTextPossible()).isFalse();
        assertThat(read(full("IMAP_ALLOW_PLAINTEXT", "true")).plainTextPossible()).isFalse();
        assertThat(read(full("IMAP_PORT", "143")).plainTextPossible()).isFalse();
    }

    // ---- connection properties (design.md 0절 14) ----

    @Test
    @DisplayName("S12-AC-02: on 993 the connection is imap with implicit TLS, the timeouts, checks and peek")
    void s12Ac02_propertiesOn993() {
        Properties p = read(full()).imapProperties();
        assertThat(p.getProperty("mail.store.protocol")).isEqualTo("imap");
        assertThat(p.getProperty("mail.imap.host")).isEqualTo(HOST);
        assertThat(p.getProperty("mail.imap.port")).isEqualTo("993");
        assertThat(p.getProperty("mail.imap.ssl.enable")).isEqualTo("true");
        assertThat(p.getProperty("mail.imap.starttls.enable")).isNotEqualTo("true");
        assertThat(p.getProperty("mail.imap.connectiontimeout")).isEqualTo("10000");
        assertThat(p.getProperty("mail.imap.timeout")).isEqualTo("30000");
        assertThat(Integer.parseInt(p.getProperty("mail.imap.writetimeout"))).isPositive();
        assertThat(p.getProperty("mail.imap.ssl.checkserveridentity")).isEqualTo("true");
        assertThat(p.getProperty("mail.imap.peek")).isEqualTo("true");
        assertThat(p.getProperty("mail.debug")).isEqualTo("false");
        assertThat(p.stringPropertyNames()).noneMatch(name -> name.startsWith("mail.imaps."));
    }

    @Test
    @DisplayName("S12-AC-02: on another port STARTTLS is required unless plain text is allowed")
    void s12Ac02_propertiesOnAnotherPort() {
        Properties required = read(full("IMAP_PORT", "143")).imapProperties();
        assertThat(required.getProperty("mail.store.protocol")).isEqualTo("imap");
        assertThat(required.getProperty("mail.imap.port")).isEqualTo("143");
        assertThat(required.getProperty("mail.imap.starttls.enable")).isEqualTo("true");
        assertThat(required.getProperty("mail.imap.starttls.required")).isEqualTo("true");
        assertThat(required.getProperty("mail.imap.ssl.enable")).isNotEqualTo("true");
        assertThat(required.getProperty("mail.imap.connectiontimeout")).isEqualTo("10000");
        assertThat(required.getProperty("mail.imap.timeout")).isEqualTo("30000");
        assertThat(required.getProperty("mail.imap.ssl.checkserveridentity")).isEqualTo("true");

        Properties allowed = read(full("IMAP_PORT", "143", "IMAP_ALLOW_PLAINTEXT", "true")).imapProperties();
        assertThat(allowed.getProperty("mail.imap.starttls.enable")).isEqualTo("true");
        assertThat(allowed.getProperty("mail.imap.starttls.required")).isEqualTo("false");
    }

    @Test
    @DisplayName("S12-AC-02: plain text allowed on 993 still uses implicit TLS")
    void s12Ac02_allowedOn993StillTls() {
        Properties p = read(full("IMAP_ALLOW_PLAINTEXT", "true")).imapProperties();
        assertThat(p.getProperty("mail.imap.ssl.enable")).isEqualTo("true");
    }

    // ---- no secrets (S12-AC-01) ----

    @Test
    @DisplayName("S12-AC-01: neither the problems, the properties nor toString hold the password or the user")
    void s12Ac01_noSecrets() {
        MailInSettings broken = read(full("IMAP_HOST", null, "IMAP_PORT", "70000",
                "MAIL_INBOUND_ADDRESS", "bad+addr@example.com", "IMAP_FOLDER", "Box-7f3",
                "IMAP_PROCESSED_FOLDER", "box-7F3"));
        assertThat(broken.problems()).hasSize(4);
        for (String problem : broken.problems()) {
            assertThat(problem).doesNotContain(PASSWORD, USER, "70000", "bad+addr", "Box-7f3", "box-7F3");
        }
        assertThat(broken.toString()).doesNotContain(PASSWORD, USER);

        MailInSettings on = read(full());
        assertThat(on.imapProperties().values()).noneMatch(v -> v.toString().contains(PASSWORD));
        assertThat(on.toString()).doesNotContain(PASSWORD, USER);
    }
}
