package io.popdesk.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** When the audit log's outside copy is on, by the settings alone. */
class AuditCopyRowsTest {

    private static boolean enabled(String url, String sandbox) {
        Map<String, String> settings = new HashMap<>();
        settings.put("AUDIT_FORWARD_URL", url);
        settings.put("POPDESK_SANDBOX", sandbox);
        return AuditCopyRows.enabled(settings::get);
    }

    @Test
    @DisplayName("S1B-AC-31 without an address, or with an empty one, the copy is off")
    void noAddressIsOff() {
        assertThat(enabled(null, null)).isFalse();
        assertThat(enabled("", null)).isFalse();
        assertThat(enabled("  ", "false")).isFalse();
    }

    @Test
    @DisplayName("S1B-AC-29 with an address the copy is on, whatever POPDESK_SANDBOX holds besides true")
    void addressIsOn() {
        assertThat(enabled("https://siem.example/in", null)).isTrue();
        assertThat(enabled("https://siem.example/in", "false")).isTrue();
        assertThat(enabled("https://siem.example/in", "")).isTrue();
    }

    @Test
    @DisplayName("S1B-AC-31 POPDESK_SANDBOX=true turns the copy off although the address is set")
    void sandboxIsOff() {
        assertThat(enabled("https://siem.example/in", "true")).isFalse();
        assertThat(enabled("https://siem.example/in", " TRUE ")).isFalse();
    }
}
