package io.popdesk.branding;

import static io.popdesk.photo.PhotoSteps.cacheControl;
import static io.popdesk.photo.PhotoSteps.decodePng;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.photo.PhotoSteps;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Session;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * The public logo addresses and answers (S9-AC-03, 04, BRAND-2, TM-19): with a logo for one theme only, both
 * addresses of the public branding are that theme's; a logo is served without a session on both hosts as
 * {@code image/png} with {@code nosniff} and a strong {@code ETag}, kept a year when {@code v} is its fingerprint and
 * asked again ({@code no-cache}) otherwise; a theme without a logo, or not a theme, is 404.
 */
class BrandingLogoServeTest extends BrandingLogoAppTest {

    private static String other(String theme) {
        return theme.equals("light") ? "dark" : "light";
    }

    @ParameterizedTest(name = "only {0}")
    @ValueSource(strings = {"light", "dark"})
    @DisplayName("S9-AC-03 with no logo both public addresses are null; with a logo for one theme only both addresses "
            + "on both hosts are that theme's path, the admin's other logo is null, and the other path is 404")
    void oneLogoServesBothAddresses(String theme) {
        startWithoutLogos();
        Session uploader = uploader();
        for (ApiClient client : List.of(portal(), console())) {
            JsonNode none = publicLogos(client);
            assertThat(none.path("light").isNull() && none.path("dark").isNull())
                    .as("both logo addresses are null without a logo: %s", none).isTrue();
        }

        JsonNode answer = uploadPng(uploader, theme, PhotoSteps.png(240, 60, 40));

        String address = answer.path("url").asString(null);
        versionOf(address, theme);
        for (ApiClient client : List.of(portal(), console())) {
            JsonNode logos = publicLogos(client);
            assertThat(logos.path("light").asString(null)).as("light address on %s", client).isEqualTo(address);
            assertThat(logos.path("dark").asString(null)).as("dark address on %s", client).isEqualTo(address);
        }
        JsonNode admin = adminLogos(uploader);
        assertThat(canonical(admin.path(theme))).as("the admin's %s logo", theme).isEqualTo(canonical(answer));
        assertThat(admin.path(other(theme)).isNull()).as("the admin's %s logo is null: %s", other(theme), admin)
                .isTrue();
        assertRefused(served(portal(), PUBLIC_LOGOS + other(theme)), 404, "NOT_FOUND");
    }

    @Test
    @DisplayName("S9-AC-04 the logo at its address is image/png with nosniff, a strong ETag that is the fingerprint "
            + "and the v of the address, and Cache-Control public, max-age=31536000, immutable on both hosts without a "
            + "session; If-None-Match with the ETag is 304 without a body")
    void logoAtItsAddressIsKeptAYear() {
        Session uploader = uploader();
        String address = uploadPng(uploader, "light", PhotoSteps.png(240, 60, 41)).path("url").asString(null);
        String v = versionOf(address, "light");

        for (ApiClient client : List.of(portal(), console())) {
            ApiResponse first = served(client, address);

            decodePng(first);
            assertThat(first.header("X-Content-Type-Options")).as("nosniff of %s", first).isEqualTo("nosniff");
            assertThat(cacheControl(first)).as("Cache-Control of %s", first)
                    .containsExactly("immutable", "max-age=31536000", "public");
            String etag = first.header("ETag");
            assertThat(etag).as("ETag of %s, strong", first).isNotNull().matches("\"[0-9A-Fa-f]{16}\"");
            assertThat(etag.substring(1, 17)).as("ETag of %s", first).isEqualToIgnoringCase(fingerprint(first))
                    .isEqualTo(v);

            ApiResponse same = client.get(address).header("If-None-Match", etag).send();

            assertThat(same.status()).as("%s: %s", same.request(), same.snippet()).isEqualTo(304);
            assertThat(same.body()).as("body of the 304").isEmpty();
            assertThat(same.header("ETag")).as("ETag of the 304").isEqualTo(etag);
        }
    }

    @Test
    @DisplayName("S9-AC-04 without v, with another v, and at the address of a replaced logo, the logo is answered "
            + "with Cache-Control no-cache and not immutable, and the replaced address gives the new logo")
    void otherAddressesAreAskedAgain() {
        Session uploader = uploader();
        String old = uploadPng(uploader, "dark", PhotoSteps.png(240, 60, 42)).path("url").asString(null);
        String address = uploadPng(uploader, "dark", PhotoSteps.png(240, 60, 43)).path("url").asString(null);
        assertThat(address).as("the address of the new logo").isNotEqualTo(old);
        byte[] current = served(console(), address).expectStatus(200).body();

        for (String other : List.of(PUBLIC_LOGOS + "dark", PUBLIC_LOGOS + "dark?v=0000000000000000", old)) {
            ApiResponse answer = served(portal(), other);

            decodePng(answer);
            assertThat(cacheControl(answer)).as("Cache-Control of %s", answer).contains("no-cache")
                    .doesNotContain("immutable");
            assertThat(answer.header("X-Content-Type-Options")).as("nosniff of %s", answer).isEqualTo("nosniff");
            assertThat(answer.body()).as("the logo at %s", other).isEqualTo(current);
        }
    }

    @Test
    @DisplayName("S9-AC-04 a theme without a logo and a theme that is neither light nor dark are 404 NOT_FOUND on "
            + "both hosts without a session, while the theme with a logo is 200")
    void missingAndUnknownThemesAreNotFound() {
        startWithoutLogos();
        uploadPng(uploader(), "light", PhotoSteps.png(240, 60, 44));

        for (ApiClient client : List.of(portal(), console())) {
            decodePng(served(client, PUBLIC_LOGOS + "light"));
            for (String theme : List.of("dark", "blue", "LIGHT")) {
                assertRefused(served(client, PUBLIC_LOGOS + theme), 404, "NOT_FOUND");
            }
        }
    }
}
