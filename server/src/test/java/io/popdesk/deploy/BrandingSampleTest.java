package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.photo.PhotoSteps;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * What the sample generator of this version ({@code deploy/upgrade/make-sample.py}) leaves of slice 9 (S9-AC-34,
 * {@code design.md} 6절): the branding "Sample Desk" with the accent color {@code #6D28D9} and one light logo. The
 * installation started on that sample gives the same branding on both hosts without a session, and serves the logo.
 */
@Tag("deploy")
class BrandingSampleTest {

    private final String project = "pdt-branding-sample-" + UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void cleanUp() {
        IssueInstallSteps.removeProject(project);
    }

    @Test
    @DisplayName("S9-AC-34 an installation started on the sample of this version gives its branding: Sample Desk, "
            + "the light accent #6D28D9, and both logo addresses at the one light logo, which is served as a PNG")
    void sampleInstallationGivesTheSampleBranding() {
        Path sample = IssueInstallSteps.makeSample(project);

        try (Stack stack = IssueInstallSteps.startedOn("brandsample", sample.resolve("sample.dump"))) {
            for (ApiClient client : List.of(stack.portal(), stack.console())) {
                JsonNode branding = client.get("/api/public/branding").expect(200).json();

                assertThat(branding.path("displayName").asString(null)).as("display name on %s", client)
                        .isEqualTo("Sample Desk");
                assertThat(branding.path("colors").path("light").path("accent").asString(null))
                        .as("light accent on %s: %s", client, branding).isEqualTo("#6D28D9");
                String light = branding.path("logos").path("light").asString(null);
                assertThat(light).as("light logo address on %s", client).isNotNull()
                        .startsWith("/api/public/branding/logos/light?v=");
                assertThat(branding.path("logos").path("dark").asString(null)).as("dark logo address on %s", client)
                        .isEqualTo(light);
                ApiResponse logo = client.get(light).send();
                PhotoSteps.decodePng(logo);
            }
        }
    }
}
