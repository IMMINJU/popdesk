package io.popdesk.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** How the two sandbox settings are read, by the settings alone. */
class SandboxSettingsTest {

    private static Sandbox sandbox(String on, String mailTo) {
        Map<String, String> settings = new HashMap<>();
        settings.put("POPDESK_SANDBOX", on);
        settings.put("POPDESK_SANDBOX_MAIL_TO", mailTo);
        return Sandbox.of(settings::get);
    }

    @Test
    @DisplayName("S4B-AC-01 true in any case, with spaces around it, is a sandbox, and its address is read")
    void trueIsASandbox() {
        Sandbox sandbox = sandbox(" True ", " ops@example.test ");

        assertThat(sandbox.on()).isTrue();
        assertThat(sandbox.mailTo()).contains("ops@example.test");
        assertThat(sandbox.mailOff()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    @DisplayName("S4B-AC-03 a sandbox whose address is empty has none: mail is off")
    void emptyAddressIsNone(String mailTo) {
        Sandbox sandbox = sandbox("true", mailTo);

        assertThat(sandbox.mailTo()).isEmpty();
        assertThat(sandbox.mailOff()).isTrue();
        assertThat(sandbox(null, null).mailOff()).as("mail off outside a sandbox").isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "1", "yes", "", "on"})
    @DisplayName("S4B-AC-06 any other value is no sandbox, and then the address is not read, right or wrong")
    void otherValuesAreNoSandbox(String on) {
        Sandbox sandbox = sandbox(on, "not an address");

        assertThat(sandbox.on()).isFalse();
        assertThat(sandbox.mailTo()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ops", "@example.test", "ops@", "a@x.test,b@y.test", "a@x.test b@y.test",
        "Ops <ops@example.test>", "a@b@c"})
    @DisplayName("S4B-AC-06 a sandbox address that is no single email address is refused, and the reason names "
            + "the key and not the value")
    void badAddressIsRefused(String mailTo) {
        assertThatThrownBy(() -> sandbox("true", mailTo)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("POPDESK_SANDBOX_MAIL_TO").hasMessageNotContaining(mailTo);
    }
}
