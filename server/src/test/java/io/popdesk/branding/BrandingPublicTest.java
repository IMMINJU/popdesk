package io.popdesk.branding;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.Fixtures.Session;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The public branding after an admin saved one (S9-AC-01, S9-AC-02): anyone on either host reads the display name
 * and the made values, and nothing an admin reads besides (no version, accent as sent, contrast, logo size).
 */
class BrandingPublicTest extends BrandingAppTest {

    @Test
    @DisplayName("S9-AC-01, S9-AC-02 after an admin saved a display name and an accent color, GET "
            + "/api/public/branding on either host, signed out or signed in, answers that name, the eight made "
            + "values as #RRGGBB in capitals, the same as the admin reads, logos null, and no other field")
    void publicReadGivesTheSavedBranding() {
        Session admin = fixtures.signInAdmin();
        Session customer = fixtures.customer(admin, fixtures.createCompany(admin).code());
        deleteLogos(admin);
        String name = uniqueName();
        JsonNode saved = save(admin, name, "#6d28d9");

        for (ApiClient client : List.of(portal(), console(), customer.client(), admin.client())) {
            JsonNode branding = publicBranding(client);

            String what = client.host() + " " + branding;
            assertThat(keys(branding)).as("fields on %s", what)
                    .containsExactlyInAnyOrder("displayName", "colors", "logos");
            assertThat(branding.path("displayName").asString(null)).as("displayName on %s", what).isEqualTo(name);
            assertMadeValues(branding.path("colors"), what);
            assertThat(branding.path("colors").path("light").path("accent").asString(null))
                    .as("light accent on %s", what).isEqualTo("#6D28D9");
            assertThat(canonical(branding.path("colors"))).as("colors on %s, as the admin's", what)
                    .isEqualTo(canonical(saved.path("colors")));
            assertThat(keys(branding.path("logos"))).as("logos on %s", what).containsExactlyInAnyOrder("light", "dark");
            assertThat(branding.path("logos").path("light").isNull()).as("light logo null on %s", what).isTrue();
            assertThat(branding.path("logos").path("dark").isNull()).as("dark logo null on %s", what).isTrue();
        }
    }
}
