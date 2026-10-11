package io.popdesk.start;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The 503 an install without a first admin gives to business requests says that its body is JSON. */
class SetupRequiredContentTypeTest extends OwnStartTest {

    @Test
    @DisplayName("S1A-AC-70 the 503 SETUP_REQUIRED of an install without first admin values has "
            + "Content-Type: application/json")
    void setupRequiredSaysJson() {
        Database db = postgres.newDatabase();
        try (Boot app = Boot.start(Settings.firstInstall(db).without("POPDESK_ADMIN_EMAIL")
                .without("POPDESK_ADMIN_PASSWORD"))) {
            app.assertStarted();

            for (ApiResponse response : List.of(app.console().get("/api/console/me").send(),
                    app.portal().get("/api/portal/me").send())) {
                assertThat(response.status()).as(response + " " + response.snippet()).isEqualTo(503);
                assertThat(response.errorCode()).as(response.toString()).isEqualTo("SETUP_REQUIRED");
                assertThat(response.header("Content-Type")).as("Content-Type of %s", response).isNotNull();
                assertThat(response.header("Content-Type").replace(" ", "").toLowerCase(Locale.ROOT))
                        .as("Content-Type of %s", response)
                        .matches("application/json(;charset=utf-8)?");
            }
        }
    }
}
