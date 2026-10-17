package io.popdesk.branding;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.photo.PhotoSteps;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Session;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Removing a logo (S9-AC-17), what a logo change does not touch (S9-AC-18: the branding's version), and the upload
 * limit (S9-AC-19: 10 a minute per admin, counted apart from profile photos, refused uploads included).
 */
class BrandingLogoChangeTest extends BrandingLogoAppTest {

    @Test
    @DisplayName("S9-AC-17 deleting a logo is 204: the admin's logo and the public address are null and its path is "
            + "404; deleting it again is 204 and leaves no second audit entry")
    void deleteRemovesTheLogo() {
        startWithoutLogos();
        Session uploader = uploader();
        String address = uploadPng(uploader, "dark", PhotoSteps.png(200, 50, 50)).path("url").asString(null);
        served(portal(), address).expectStatus(200);

        ApiResponse deleted = uploader.client().delete(LOGOS + "dark").send();

        assertThat(deleted.status()).as("%s: %s", deleted.request(), deleted.snippet()).isEqualTo(204);
        assertThat(adminLogos(uploader).path("dark").isNull()).as("the admin's dark logo is null").isTrue();
        JsonNode logos = publicLogos(portal());
        assertThat(logos.path("light").isNull() && logos.path("dark").isNull())
                .as("both public addresses are null: %s", logos).isTrue();
        assertRefused(served(portal(), address), 404, "NOT_FOUND");
        assertThat(logoEntries(uploader)).as("logo entries of the admin after the upload and the delete").hasSize(2);

        ApiResponse again = uploader.client().delete(LOGOS + "dark").send();

        assertThat(again.status()).as("%s: %s", again.request(), again.snippet()).isEqualTo(204);
        assertThat(logoEntries(uploader)).as("logo entries of the admin after deleting nothing").hasSize(2);
    }

    @Test
    @DisplayName("S9-AC-18 uploading, replacing, and deleting a logo leave the branding's version as it was while the "
            + "logo address changes with the logo, and a save with the version read before them goes through")
    void logoChangesKeepTheVersion() {
        Session first = fixtures.signInAdmin();
        Session uploader = uploader();
        JsonNode start = save(first, uniqueName(), EMERALD);
        long version = version(start);

        String one = uploadPng(uploader, "light", PhotoSteps.png(200, 50, 51)).path("url").asString(null);
        long afterUpload = version(branding(first));
        String two = uploadPng(uploader, "light", PhotoSteps.png(200, 50, 52)).path("url").asString(null);
        long afterReplace = version(branding(first));
        uploader.client().delete(LOGOS + "light").expect(204);
        long afterDelete = version(branding(first));

        assertThat(List.of(afterUpload, afterReplace, afterDelete)).as("versions after the logo changes")
                .containsOnly(version);
        assertThat(versionOf(two, "light")).as("v of the replaced logo's address").isNotEqualTo(versionOf(one, "light"));
        String name = uniqueName();
        JsonNode saved = update(first, body(version, name, EMERALD)).expect(200).json();
        assertThat(version(saved)).as("version after the save").isEqualTo(version + 1);
        assertThat(saved.path("displayName").asString(null)).as("the saved name").isEqualTo(name);
    }

    @Test
    @DisplayName("S9-AC-19 the 11th logo upload of an admin within a minute is 429 RATE_LIMITED with Retry-After, "
            + "uploads refused with 413 and 400 counted, and changes nothing; profile photos are counted apart, and "
            + "another admin is not limited")
    void eleventhUploadInAMinuteIsLimited() {
        Session uploader = uploader();
        for (int i = 0; i < 5; i++) {
            PhotoSteps.upload(uploader, "photo.png", "image/png", PhotoSteps.png(64, 64, 300 + i)).expectStatus(201);
        }
        for (int i = 0; i < 8; i++) {
            ApiResponse taken = upload(uploader, "light", "logo-" + i + ".png", "image/png",
                    PhotoSteps.png(120, 40, 310 + i));
            assertThat(taken.status()).as("upload %d: %s", i + 1, taken.snippet()).isEqualTo(200);
        }
        JsonNode kept = adminLogos(uploader);
        assertThat(upload(uploader, "light", "big.png", "image/png", PhotoSteps.pngOfSize(MAX_BYTES + 1, 320)).status())
                .as("the 9th upload, over 512 KB").isEqualTo(413);
        assertThat(upload(uploader, "light", "logo.svg", "image/svg+xml", svg()).status())
                .as("the 10th upload, an SVG").isEqualTo(400);

        ApiResponse eleventh = upload(uploader, "light", "logo-10.png", "image/png", PhotoSteps.png(120, 40, 321));

        assertRefused(eleventh, 429, "RATE_LIMITED");
        assertThat(eleventh.header("Retry-After")).as("Retry-After").isNotNull().matches("[1-9][0-9]*");
        assertThat(Integer.parseInt(eleventh.header("Retry-After"))).as("Retry-After in seconds").isBetween(1, 60);
        assertThat(canonical(adminLogos(uploader))).as("the logos after the 429").isEqualTo(canonical(kept));
        assertThat(PhotoSteps.upload(uploader, "photo.png", "image/png", PhotoSteps.png(64, 64, 322)).status())
                .as("the admin's 6th profile photo after the 429").isEqualTo(201);
        assertThat(upload(uploader(), "light", "logo.png", "image/png", PhotoSteps.png(120, 40, 323)).status())
                .as("a logo upload of another admin").isEqualTo(200);
    }
}
