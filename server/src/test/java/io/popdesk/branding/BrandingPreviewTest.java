package io.popdesk.branding;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Session;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * The accent preview (S9-AC-12) and the values made from an accent color (S9-AC-13). The exact values are checked
 * here for two colors only, the ones {@code design.md} 3절 measured; the other cases are the unit tests of the
 * calculation (C-10).
 */
class BrandingPreviewTest extends BrandingAppTest {

    /** {@code #047857} as {@code design.md} 3절 measured it: light accent, hover, on, subtle; then dark. */
    private static final List<String> EMERALD_LIGHT = List.of("#047857", "#005E43", "#FFFFFF", "#DBF8EB");
    private static final List<String> EMERALD_DARK = List.of("#6ECBA5", "#88E5BF", "#191817", "#0A3123");

    @Test
    @DisplayName("S9-AC-12 the preview answers the made values, the two contrasts, and acceptable, and saves nothing")
    void previewSavesNothing() {
        Session admin = fixtures.signInAdmin();
        JsonNode before = save(admin, uniqueName(), "#6D28D9");

        ApiResponse emerald = preview(admin, EMERALD).expectStatus(200);
        ApiResponse rose = preview(admin, "#E11D48").expectStatus(200);

        JsonNode answer = emerald.json();
        assertThat(keys(answer)).as("fields of %s", answer).containsExactlyInAnyOrder("colors", "contrast", "acceptable");
        assertMadeValues(answer.path("colors"), "the preview of " + EMERALD);
        assertThat(keys(answer.path("contrast"))).as("contrast").containsExactlyInAnyOrder("light", "dark");
        assertThat(answer.path("contrast").path("light").isNumber()).as("light contrast is a number").isTrue();
        assertThat(answer.path("contrast").path("dark").isNumber()).as("dark contrast is a number").isTrue();
        assertThat(answer.path("acceptable").isBoolean() && answer.path("acceptable").asBoolean())
                .as("acceptable of %s", answer).isTrue();
        assertThat(rose.json().path("acceptable").isBoolean() && !rose.json().path("acceptable").asBoolean())
                .as("acceptable of %s", rose.snippet()).isTrue();
        // 4.101 to 4.10, rounded down at the second decimal (design.md 3절).
        assertThat(rose.json().path("contrast").path("light").asDouble()).as("light contrast of #E11D48")
                .isEqualTo(4.10);
        assertThat(canonical(branding(admin))).as("the branding after the previews").isEqualTo(canonical(before));
    }

    @Test
    @DisplayName("S9-AC-12 the preview without color is 400 INVALID_INPUT with color REQUIRED")
    void colorIsRequired() {
        Session admin = fixtures.signInAdmin();

        ApiResponse refused = admin.client().get(ACCENT).send();

        assertInvalid(refused, "color", "REQUIRED");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"047857", "#04785", "#04785G", "blue"})
    @DisplayName("S9-AC-12 the preview of a color that is not # and six hexadecimal digits is 400 INVALID_INPUT with "
            + "color INVALID_FORMAT")
    void colorOfTheWrongFormat(String color) {
        Session admin = fixtures.signInAdmin();

        ApiResponse refused = preview(admin, color);

        assertInvalid(refused, "color", "INVALID_FORMAT");
    }

    @Test
    @DisplayName("S9-AC-13 #047857 makes the values design.md 3절 measured: dark accent #6ECBA5, light contrast 4.81 "
            + "(the lowest over the light backgrounds), dark contrast 7.27 (rounded down), and the same values every "
            + "time")
    void emeraldMakesTheMeasuredValues() {
        Session admin = fixtures.signInAdmin();

        JsonNode first = preview(admin, EMERALD).expectStatus(200).json();
        JsonNode again = preview(admin, EMERALD).expectStatus(200).json();

        assertThat(canonical(first.path("colors"))).as("colors of %s", EMERALD)
                .isEqualTo(canonical(json(colors(EMERALD_LIGHT, EMERALD_DARK))));
        assertThat(first.path("contrast").path("light").asDouble()).as("light contrast of %s", EMERALD).isEqualTo(4.81);
        // 7.2798 to 7.27, rounded down at the second decimal; rounding would give 7.28 (design.md 3절, 9절).
        assertThat(first.path("contrast").path("dark").asDouble()).as("dark contrast of %s", EMERALD).isEqualTo(7.27);
        assertThat(canonical(again)).as("the second preview").isEqualTo(canonical(first));
    }

    @Test
    @DisplayName("S9-AC-13 #1E293B makes the dark accent #A8B6CD, and #1e293b the same values")
    void slateMakesTheMeasuredDarkAccent() {
        Session admin = fixtures.signInAdmin();

        JsonNode capitals = preview(admin, "#1E293B").expectStatus(200).json();
        JsonNode small = preview(admin, "#1e293b").expectStatus(200).json();

        assertThat(capitals.path("colors").path("dark").path("accent").asString(null)).as("dark accent of #1E293B")
                .isEqualTo("#A8B6CD");
        assertThat(capitals.path("colors").path("light").path("accent").asString(null)).as("light accent of #1E293B")
                .isEqualTo("#1E293B");
        assertThat(canonical(small)).as("the preview of #1e293b").isEqualTo(canonical(capitals));
    }
}
