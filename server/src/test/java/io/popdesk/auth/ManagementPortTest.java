package io.popdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/**
 * Actuator lives on its own management port (8081 in production). In tests Spring Boot moves a
 * management port that differs from the HTTP port to a free one and publishes it as
 * {@code local.management.port}; when the app has no separate port, that property equals the HTTP
 * port.
 */
class ManagementPortTest extends BareAppTest {

    @Autowired
    Environment environment;

    @Test
    @DisplayName("S0-AC-15 actuator answers on the management port, which is not the HTTP port")
    void actuatorOnManagementPort() {
        String managementPort = environment.getProperty("local.management.port");
        assertThat(managementPort).as("local.management.port").isNotNull();
        assertThat(Integer.parseInt(managementPort)).as("management port vs HTTP port %s", port).isNotEqualTo(port);

        ApiResponse health = new ApiClient("localhost", Integer.parseInt(managementPort))
                .get("/actuator/health").send();

        assertThat(health.status()).as(health.toString()).isEqualTo(200);
        assertThat(health.json().path("status").asString()).isEqualTo("UP");
    }

    @Test
    @DisplayName("S0-AC-15 the HTTP port answers 404 for /actuator/** on both hosts")
    void noActuatorOnHttpPort() {
        for (ApiClient client : List.of(console(), portal())) {
            for (String path : List.of("/actuator", "/actuator/health", "/actuator/health/readiness",
                    "/actuator/prometheus", "/actuator/info")) {
                ApiResponse response = client.get(path).send();

                assertThat(response.status()).as(response.toString()).isEqualTo(404);
                assertThat(response.text()).as(response.toString())
                        .doesNotContain("\"UP\"")
                        .doesNotContain("\"groups\"")
                        .doesNotContain("_links");
            }
        }
    }
}
