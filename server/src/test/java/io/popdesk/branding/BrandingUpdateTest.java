package io.popdesk.branding;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Session;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Reading and saving the branding as an admin (S9-AC-06 to S9-AC-08): the version, a save makes the next one, the
 * same values keep it and leave no audit entry, and a stale version is 409 even with the values already saved,
 * unless the values are wrong too (400 first, as the SLA policy).
 */
class BrandingUpdateTest extends BrandingAppTest {

    private static final String LOG = "/api/console/audit-log";

    private static int entries(Session admin) {
        ApiResponse page = admin.client().get(LOG).query("actorAccountId", admin.accountId())
                .query("action", "BRANDING_CHANGED").query("size", 100).expect(200);
        JsonNode items = page.json().path("items");
        if (!items.isArray()) {
            throw new AssertionError(page.request() + ": no items array in " + page.snippet());
        }
        return items.size();
    }

    @Test
    @DisplayName("S9-AC-06 an admin reads the version, the display name, the accent color as saved, its made values, "
            + "the lowest contrast of each theme as a number, and both logos (null without one)")
    void adminReadsTheBranding() {
        Session admin = fixtures.signInAdmin();
        deleteLogos(admin);
        String name = uniqueName();
        save(admin, name, EMERALD);

        JsonNode branding = branding(admin);

        assertThat(keys(branding)).as("fields of %s", branding)
                .containsExactlyInAnyOrder("version", "displayName", "accent", "colors", "contrast", "logos");
        assertThat(branding.path("version").isIntegralNumber()).as("version of %s", branding).isTrue();
        assertThat(branding.path("displayName").asString(null)).as("displayName").isEqualTo(name);
        assertThat(branding.path("accent").asString(null)).as("accent").isEqualTo(EMERALD);
        assertMadeValues(branding.path("colors"), "the admin's branding");
        assertThat(keys(branding.path("contrast"))).as("contrast of %s", branding)
                .containsExactlyInAnyOrder("light", "dark");
        assertThat(branding.path("contrast").path("light").isNumber()).as("light contrast is a number").isTrue();
        assertThat(branding.path("contrast").path("dark").isNumber()).as("dark contrast is a number").isTrue();
        assertThat(branding.path("contrast").path("light").asDouble()).as("light contrast").isEqualTo(4.81);
        assertThat(keys(branding.path("logos"))).as("logos").containsExactlyInAnyOrder("light", "dark");
        assertThat(branding.path("logos").path("light").isNull()).as("light logo null").isTrue();
        assertThat(branding.path("logos").path("dark").isNull()).as("dark logo null").isTrue();
    }

    @Test
    @DisplayName("S9-AC-07 a save with the version read makes the next version and answers the branding as an admin "
            + "reads it; the same values sent again keep the version and leave no audit entry")
    void saveMakesTheNextVersion() {
        Session admin = fixtures.admin(fixtures.signInAdmin());
        save(admin, uniqueName(), null);
        long before = version(branding(admin));
        String name = uniqueName();

        JsonNode saved = update(admin, body(before, name, EMERALD)).expect(200).json();

        assertThat(version(saved)).as("version of the answer").isEqualTo(before + 1);
        assertThat(saved.path("displayName").asString(null)).as("displayName of the answer").isEqualTo(name);
        assertThat(saved.path("accent").asString(null)).as("accent of the answer").isEqualTo(EMERALD);
        assertThat(canonical(branding(admin))).as("the branding read again").isEqualTo(canonical(saved));
        int entriesAfterSave = entries(admin);

        JsonNode same = update(admin, body(before + 1, name, EMERALD)).expect(200).json();

        assertThat(canonical(same)).as("the answer to the same values").isEqualTo(canonical(saved));
        assertThat(version(branding(admin))).as("version after the same values").isEqualTo(before + 1);
        assertThat(entriesAfterSave).as("BRANDING_CHANGED entries of the admin after the save").isEqualTo(2);
        assertThat(entries(admin)).as("BRANDING_CHANGED entries of the admin after the same values").isEqualTo(2);
    }

    @Test
    @DisplayName("S9-AC-07 values that are the saved ones after the clean-up (the name with spaces around it and not "
            + "in NFC, the accent in small letters) keep the version and leave no audit entry")
    void cleanedUpSameValuesKeepTheVersion() {
        Session admin = fixtures.admin(fixtures.signInAdmin());
        String base = uniqueName();
        JsonNode saved = save(admin, base + " Café", "#6D28D9");
        int entriesAfterSave = entries(admin);

        // e and a combining acute accent: U+00E9 once in NFC.
        JsonNode same = update(admin, body(version(saved), "  " + base + " Café ", "#6d28d9")).expect(200)
                .json();

        assertThat(canonical(same)).as("the answer to the same values").isEqualTo(canonical(saved));
        assertThat(version(branding(admin))).as("version after the same values").isEqualTo(version(saved));
        assertThat(entriesAfterSave).as("BRANDING_CHANGED entries of the admin after the save").isEqualTo(1);
        assertThat(entries(admin)).as("BRANDING_CHANGED entries of the admin after the same values").isEqualTo(1);
    }

    @Test
    @DisplayName("S9-AC-08 two saves sent with the same version: the first makes the next version, the second is "
            + "409 VERSION_CONFLICT and changes nothing, and so is a second one sending the values already saved")
    void staleVersionIsAConflict() {
        Session admin = fixtures.signInAdmin();
        Session otherAdmin = fixtures.admin(admin);
        long read = version(branding(admin));
        String first = uniqueName();

        JsonNode saved = update(admin, body(read, first, EMERALD)).expect(200).json();
        ApiResponse late = update(otherAdmin, body(read, uniqueName(), null)).send();
        ApiResponse lateButSame = update(otherAdmin, body(read, first, EMERALD)).send();

        assertThat(version(saved)).as("version of the first save").isEqualTo(read + 1);
        assertRefused(late, 409, "VERSION_CONFLICT");
        assertRefused(lateButSame, 409, "VERSION_CONFLICT");
        assertThat(canonical(branding(admin))).as("the branding after the conflicts").isEqualTo(canonical(saved));
    }

    @Test
    @DisplayName("S9-AC-08 a save with wrong values and a stale version is 400 INVALID_INPUT, not 409, and changes "
            + "nothing")
    void wrongValuesComeBeforeTheVersion() {
        Session admin = fixtures.signInAdmin();
        JsonNode saved = save(admin, uniqueName(), EMERALD);
        long stale = version(saved) - 1;

        List<ApiResponse> refused = List.of(update(admin, body(stale, "", EMERALD)).send(),
                update(admin, body(stale, uniqueName(), "#04785")).send());

        assertInvalid(refused.get(0), "displayName", "REQUIRED");
        assertInvalid(refused.get(1), "accent", "INVALID_FORMAT");
        assertThat(canonical(branding(admin))).as("the branding after the refusals").isEqualTo(canonical(saved));
    }
}
