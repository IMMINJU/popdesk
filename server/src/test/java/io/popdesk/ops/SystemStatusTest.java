package io.popdesk.ops;

import static io.popdesk.ops.OpsChecks.assertError;
import static io.popdesk.ops.OpsChecks.fieldNames;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Session;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The system status without SMTP and webhook (OPS-3 in part): who may read it and what it says. */
class SystemStatusTest extends BareAppTest {

    static final String STATUS = "/api/console/system/status";
    static final String FAILED = "/api/console/system/failed-deliveries";

    static final Set<String> CHANNEL_FIELDS = Set.of("enabled", "lastSuccessAt", "queued", "failed");

    @Test
    @DisplayName("S0-AC-81 without SMTP and webhook both channels are off, each with exactly the four fields")
    void channelsOff() {
        Session admin = fixtures.signInAdmin();

        JsonNode status = admin.client().get(STATUS).expect(200).json();

        assertThat(fieldNames(status)).isEqualTo(Set.of("mail", "webhook"));
        for (String channel : Set.of("mail", "webhook")) {
            JsonNode c = status.path(channel);
            assertThat(fieldNames(c)).as(channel).isEqualTo(CHANNEL_FIELDS);
            assertThat(c.path("enabled").isBoolean()).as("%s.enabled", channel).isTrue();
            assertThat(c.path("enabled").asBoolean()).as("%s.enabled", channel).isFalse();
            assertThat(c.path("queued").isIntegralNumber()).as("%s.queued", channel).isTrue();
            assertThat(c.path("failed").isIntegralNumber()).as("%s.failed", channel).isTrue();
        }
        assertThat(status.path("webhook").path("lastSuccessAt").isNull()).as("webhook.lastSuccessAt").isTrue();
    }

    @Test
    @DisplayName("S0-AC-81 with nothing failed the failed list is an empty page")
    void noFailedDeliveries() {
        Session admin = fixtures.signInAdmin();

        JsonNode page = admin.client().get(FAILED).expect(200).json();

        assertThat(fieldNames(page)).isEqualTo(Set.of("items", "page", "size", "totalElements", "totalPages"));
        assertThat(page.path("items").isArray()).isTrue();
        assertThat(page.path("items")).isEmpty();
        assertThat(page.path("totalElements").asLong(-1)).isZero();
    }

    @Test
    @DisplayName("S0-AC-81 an agent gets 403 for the status and the failed list")
    void agentForbidden() {
        Session admin = fixtures.signInAdmin();
        Session agent = fixtures.agent(admin);

        assertError(agent.client().get(STATUS).send(), 403, "FORBIDDEN");
        assertError(agent.client().get(FAILED).send(), 403, "FORBIDDEN");
    }

    @Test
    @DisplayName("S0-AC-81 without a session the status and the failed list are 401")
    void signedOut() {
        ApiClient anonymous = console();

        assertError(anonymous.get(STATUS).send(), 401, "UNAUTHENTICATED");
        assertError(anonymous.get(FAILED).send(), 401, "UNAUTHENTICATED");
    }

    @Test
    @DisplayName("S0-AC-81 the failed list pages by COM-4: size out of range is 400")
    void failedListPaging() {
        Session admin = fixtures.signInAdmin();

        for (int size : new int[] {0, 101}) {
            assertError(admin.client().get(FAILED).query("size", size).send(), 400, "INVALID_INPUT");
        }
        assertError(admin.client().get(FAILED).query("page", 0).send(), 400, "INVALID_INPUT");
        JsonNode page = admin.client().get(FAILED).query("page", 2).query("size", 5).expect(200).json();
        assertThat(page.path("page").asInt()).isEqualTo(2);
        assertThat(page.path("size").asInt()).isEqualTo(5);
    }
}
