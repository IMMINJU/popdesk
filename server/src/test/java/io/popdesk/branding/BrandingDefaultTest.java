package io.popdesk.branding;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Session;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The branding of an installation that never saved one (S9-AC-05): the default, read publicly and by an admin.
 * This is the "bare" context, where no test changes the branding ({@link BrandingAppTest}), so the default row of
 * V21 is what it holds.
 */
class BrandingDefaultTest extends BareAppTest {

    private static List<String> keys(JsonNode node) {
        return new ArrayList<>(node.propertyNames());
    }

    @Test
    @DisplayName("S9-AC-01, S9-AC-02, S9-AC-05 GET /api/public/branding on either host, signed out or signed in, "
            + "answers 200 with the default and nothing else: displayName popdesk, colors null, both logos null")
    void publicReadGivesTheDefault() {
        Session admin = fixtures.signInAdmin();
        Session customer = fixtures.customer(admin, fixtures.createCompany(admin).code());

        for (ApiClient client : List.of(portal(), console(), customer.client(), admin.client())) {
            JsonNode branding = client.get(BrandingAppTest.PUBLIC).expect(200).json();

            String what = client.host() + " " + branding;
            assertThat(keys(branding)).as("fields on %s", what)
                    .containsExactlyInAnyOrder("displayName", "colors", "logos");
            assertThat(branding.path("displayName").asString(null)).as("displayName on %s", what)
                    .isEqualTo("popdesk");
            assertThat(branding.path("colors").isNull()).as("colors null on %s", what).isTrue();
            assertThat(keys(branding.path("logos"))).as("logos on %s", what).containsExactlyInAnyOrder("light", "dark");
            assertThat(branding.path("logos").path("light").isNull()).as("light logo null on %s", what).isTrue();
            assertThat(branding.path("logos").path("dark").isNull()).as("dark logo null on %s", what).isTrue();
        }
    }

    @Test
    @DisplayName("S9-AC-06, S9-AC-33 an admin reads the default branding: version 1, displayName popdesk, accent, "
            + "colors, and contrast null, and both logos null")
    void adminReadsTheDefault() {
        Session admin = fixtures.signInAdmin();

        JsonNode branding = admin.client().get(BrandingAppTest.BRANDING).expect(200).json();

        assertThat(keys(branding)).as("fields of %s", branding)
                .containsExactlyInAnyOrder("version", "displayName", "accent", "colors", "contrast", "logos");
        assertThat(branding.path("version").asLong(-1)).as("version").isEqualTo(1);
        assertThat(branding.path("displayName").asString(null)).as("displayName").isEqualTo("popdesk");
        for (String nullField : List.of("accent", "colors", "contrast")) {
            assertThat(branding.path(nullField).isNull()).as("%s null in %s", nullField, branding).isTrue();
        }
        assertThat(keys(branding.path("logos"))).as("logos").containsExactlyInAnyOrder("light", "dark");
        assertThat(branding.path("logos").path("light").isNull()).as("light logo null").isTrue();
        assertThat(branding.path("logos").path("dark").isNull()).as("dark logo null").isTrue();
    }
}
