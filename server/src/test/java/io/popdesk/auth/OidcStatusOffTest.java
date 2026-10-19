package io.popdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The {@code oidc} part of the system status in the shared context, which has no OIDC settings (S11-AC-30). */
class OidcStatusOffTest extends BareAppTest {

    @Test
    @DisplayName("S11-AC-30 without the OIDC settings the status says enabled false, no apps, no last success, "
            + "0 provider failures, and no last failure, with exactly the five fields")
    void statusSaysOff() {
        Session admin = fixtures.signInAdmin();

        JsonNode status = admin.client().get(OidcStatusTest.STATUS).expect(200).json().path("oidc");

        assertThat(OidcFlow.fieldNames(status)).as("fields of %s", status).isEqualTo(OidcStatusTest.OIDC_FIELDS);
        assertThat(status.path("enabled").isBoolean()).as("enabled of %s", status).isTrue();
        assertThat(status.path("enabled").booleanValue()).as("enabled of %s", status).isFalse();
        assertThat(status.path("apps").isArray()).as("apps of %s", status).isTrue();
        assertThat(status.path("apps")).as("apps of %s", status).isEmpty();
        assertThat(status.path("lastSuccessAt").isNull()).as("lastSuccessAt of %s", status).isTrue();
        assertThat(status.path("providerFailures").isIntegralNumber()).as("providerFailures of %s", status).isTrue();
        assertThat(status.path("providerFailures").asLong(-1)).as("providerFailures of %s", status).isZero();
        assertThat(status.path("lastFailure").isNull()).as("lastFailure of %s", status).isTrue();
    }
}
