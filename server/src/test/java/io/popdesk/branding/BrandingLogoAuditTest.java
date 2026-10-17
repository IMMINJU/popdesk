package io.popdesk.branding;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.photo.PhotoSteps;
import io.popdesk.support.AuditChainMath;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The audit entry of a logo change (S9-AC-21, AUDIT-1): one {@code BRANDING_LOGO_CHANGED} per upload or delete that
 * changed the logo, with the target {@code {"type": "BRANDING", "theme": "LIGHT" | "DARK"}} and {@code changes.logo}
 * as {@code {from, to}}: the fingerprint (the first 16 characters of the SHA-256 of the stored PNG) or {@code null}.
 * The test reads the entries of an admin it made itself.
 */
class BrandingLogoAuditTest extends BrandingLogoAppTest {

    private static String target(String theme) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("type", "BRANDING");
        target.put("theme", theme);
        return canonical(json(target));
    }

    private static String logoChange(String from, String to) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("from", from);
        change.put("to", to);
        return canonical(json(Map.of("logo", change)));
    }

    /** The fingerprint of an uploaded logo: the {@code v} of its address, the start of the SHA-256 of what it serves. */
    private String uploadedFingerprint(JsonNode answer, String theme) {
        String address = answer.path("url").asString(null);
        String v = versionOf(address, theme);
        assertThat(v).as("v of %s", address).isEqualToIgnoringCase(fingerprint(served(portal(), address)
                .expectStatus(200)));
        return v;
    }

    @Test
    @DisplayName("S9-AC-21 each upload or delete that changes a logo leaves one BRANDING_LOGO_CHANGED entry by the "
            + "admin, target {type BRANDING, theme LIGHT or DARK}, changes.logo {from, to} as fingerprints or null; "
            + "the same file again, deleting nothing, and a refused upload leave none, and no entry holds the file name")
    void oneEntryPerLogoChange() {
        startWithoutLogos();
        Session uploader = uploader();
        String fileName = "logo-" + Unique.number() + ".png";
        byte[] a = PhotoSteps.png(200, 50, 60);
        byte[] b = PhotoSteps.png(200, 50, 61);

        String lightA = uploadedFingerprint(upload(uploader, "light", fileName, "image/png", a).expectStatus(200)
                .json(), "light");
        upload(uploader, "light", fileName, "image/png", a).expectStatus(200);
        String lightB = uploadedFingerprint(upload(uploader, "light", fileName, "image/png", b).expectStatus(200)
                .json(), "light");
        uploader.client().delete(LOGOS + "light").expect(204);
        uploader.client().delete(LOGOS + "light").expect(204);
        String darkA = uploadedFingerprint(upload(uploader, "dark", fileName, "image/png", a).expectStatus(200)
                .json(), "dark");
        upload(uploader, "dark", "logo.svg", "image/svg+xml", svg()).expectStatus(400);

        List<JsonNode> entries = logoEntries(uploader);
        List<String> found = entries.stream().map(e -> {
            assertThat(e.path("actor").path("accountId").asLong(-1)).as("actor of %s", e)
                    .isEqualTo(uploader.accountId());
            assertThat(e.toString()).as("the entry %s", e).doesNotContain(fileName);
            return canonical(e.path("target")) + " " + canonical(e.path("changes"));
        }).toList();
        assertThat(found).as("target and changes of the admin's BRANDING_LOGO_CHANGED entries")
                .containsExactlyInAnyOrder(
                        target("LIGHT") + " " + logoChange(null, lightA),
                        target("LIGHT") + " " + logoChange(lightA, lightB),
                        target("LIGHT") + " " + logoChange(lightB, null),
                        target("DARK") + " " + logoChange(null, darkA));
        AuditChainMath.assertValid(uploader.client());
    }
}
