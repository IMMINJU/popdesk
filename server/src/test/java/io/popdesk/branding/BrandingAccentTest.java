package io.popdesk.branding;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * The accent color of a save (S9-AC-10, S9-AC-11): {@code #} and six hexadecimal digits, either letter case, kept
 * in capitals; a color whose light accent misses 4.5:1 on a light background (the light ones, not only white) is
 * refused and not saved; {@code null} is the app's own colors.
 */
class BrandingAccentTest extends BrandingAppTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"047857", "#04785", "#0478570", "#04785G", "#04-857", "red", "#047857;"})
    @DisplayName("S9-AC-10 an accent color that is not # and six hexadecimal digits is 400 INVALID_INPUT with "
            + "accent INVALID_FORMAT, and changes nothing")
    void wrongFormatChangesNothing(String accent) {
        Session admin = fixtures.signInAdmin();
        JsonNode before = save(admin, uniqueName(), EMERALD);

        ApiResponse refused = update(admin, body(version(before), uniqueName(), accent)).send();

        assertInvalid(refused, "accent", "INVALID_FORMAT");
        assertThat(canonical(branding(admin))).as("the branding after the refusal").isEqualTo(canonical(before));
    }

    @Test
    @DisplayName("S9-AC-10 an accent color in small letters is taken and kept in capitals, in the answer, read "
            + "again, as the light accent, and publicly")
    void smallLettersAreKeptInCapitals() {
        Session admin = fixtures.signInAdmin();
        save(admin, uniqueName(), EMERALD);

        JsonNode saved = update(admin, body(version(branding(admin)), uniqueName(), "#6d28d9")).expect(200).json();

        assertThat(saved.path("accent").asString(null)).as("accent of the answer").isEqualTo("#6D28D9");
        JsonNode again = branding(admin);
        assertThat(again.path("accent").asString(null)).as("accent read again").isEqualTo("#6D28D9");
        assertThat(again.path("colors").path("light").path("accent").asString(null)).as("light accent")
                .isEqualTo("#6D28D9");
        assertThat(publicBranding(console()).path("colors").path("light").path("accent").asString(null))
                .as("public light accent").isEqualTo("#6D28D9");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"#FACC15", "#E11D48", "#e11d48"})
    @DisplayName("S9-AC-11 an accent color that is not acceptable is 400 INVALID_INPUT with accent LOW_CONTRAST and is "
            + "not saved: #FACC15 misses white, #E11D48 passes white (4.70) but misses the light backgrounds (4.10)")
    void lowContrastIsNotSaved(String accent) {
        Session admin = fixtures.signInAdmin();
        JsonNode before = save(admin, uniqueName(), EMERALD);

        ApiResponse refused = update(admin, body(version(before), before.path("displayName").asString(), accent))
                .send();

        assertInvalid(refused, "accent", "LOW_CONTRAST");
        assertThat(canonical(branding(admin))).as("the branding after the refusal").isEqualTo(canonical(before));
        assertThat(publicBranding(portal()).path("colors").path("light").path("accent").asString(null))
                .as("public light accent after the refusal").isEqualTo(EMERALD);
    }

    @Test
    @DisplayName("S9-AC-10 an accent color of null saves the app's own colors: accent, colors, and contrast null, "
            + "and colors null publicly")
    void nullIsTheDefaultColors() {
        Session admin = fixtures.signInAdmin();
        save(admin, uniqueName(), EMERALD);

        JsonNode saved = update(admin, body(version(branding(admin)), uniqueName(), null)).expect(200).json();

        for (String field : new String[] {"accent", "colors", "contrast"}) {
            assertThat(saved.path(field).isNull()).as("%s null in %s", field, saved).isTrue();
        }
        assertThat(canonical(branding(admin))).as("the branding read again").isEqualTo(canonical(saved));
        assertThat(publicBranding(portal()).path("colors").isNull()).as("public colors null").isTrue();
    }
}
