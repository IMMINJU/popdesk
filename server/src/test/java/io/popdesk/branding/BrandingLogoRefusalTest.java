package io.popdesk.branding;

import static io.popdesk.photo.PhotoSteps.decodePng;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.popdesk.photo.PhotoSteps;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Session;
import java.util.Arrays;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * What a logo upload refuses (S9-AC-16, TM-19, TM-65) and the themes there are (S9-AC-17): a file over 512 KB is
 * 413, what is not a PNG, JPEG, or WebP by its content is 400 {@code UNSUPPORTED_IMAGE} whatever its name says, too
 * many pixels is 400 {@code IMAGE_TOO_LARGE}, and a theme other than {@code light} and {@code dark} is 404. In every
 * case the logo there was before stays.
 */
class BrandingLogoRefusalTest extends BrandingLogoAppTest {

    static Stream<Arguments> files() {
        return Stream.of(
                arguments(named("a PNG of exactly 524,288 bytes",
                        (Supplier<byte[]>) () -> PhotoSteps.pngOfSize(MAX_BYTES, 31)), "logo.png", "image/png", 200, null),
                arguments(named("a PNG of 524,289 bytes",
                                (Supplier<byte[]>) () -> PhotoSteps.pngOfSize(MAX_BYTES + 1, 32)),
                        "logo.png", "image/png", 413, "PAYLOAD_TOO_LARGE"),
                arguments(named("an SVG sent as image/svg+xml", (Supplier<byte[]>) BrandingLogoAppTest::svg),
                        "logo.svg", "image/svg+xml", 400, "UNSUPPORTED_IMAGE"),
                arguments(named("an SVG named logo.png and sent as image/png", (Supplier<byte[]>) BrandingLogoAppTest::svg),
                        "logo.png", "image/png", 400, "UNSUPPORTED_IMAGE"),
                arguments(named("a GIF named logo.png and sent as image/png",
                                (Supplier<byte[]>) () -> PhotoSteps.gif(200, 50, 33)),
                        "logo.png", "image/png", 400, "UNSUPPORTED_IMAGE"),
                arguments(named("an HTML page named logo.png and sent as image/png",
                                (Supplier<byte[]>) BrandingLogoAppTest::html),
                        "logo.png", "image/png", 400, "UNSUPPORTED_IMAGE"),
                arguments(named("the first bytes of a PNG followed by noise", (Supplier<byte[]>) () -> {
                    byte[] broken = PhotoSteps.png(200, 50, 34);
                    Arrays.fill(broken, 8, broken.length, (byte) 0x5A);
                    return broken;
                }), "logo.png", "image/png", 400, "UNSUPPORTED_IMAGE"),
                arguments(named("PNG 5001x5000, over 25 million pixels",
                                (Supplier<byte[]>) () -> PhotoSteps.solidPng(5001, 5000)),
                        "logo.png", "image/png", 400, "IMAGE_TOO_LARGE"),
                arguments(named("a PNG whose header says 30000x30000, with no pixel data",
                                (Supplier<byte[]>) () -> PhotoSteps.pngHeaderOnly(30000, 30000)),
                        "logo.png", "image/png", 400, "IMAGE_TOO_LARGE"),
                arguments(named("a WebP whose header says 16383x16383, with no pixel data",
                                (Supplier<byte[]>) () -> PhotoSteps.webpHeaderOnly(16383, 16383)),
                        "logo.webp", "image/webp", 400, "IMAGE_TOO_LARGE"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("files")
    @DisplayName("S9-AC-16 512 KB is taken and one byte more is 413 PAYLOAD_TOO_LARGE; an SVG, a GIF, HTML named "
            + ".png, or a broken file is 400 UNSUPPORTED_IMAGE; too many pixels is 400 IMAGE_TOO_LARGE; a refused file "
            + "leaves the logo there was")
    void judgedBySizeAndContent(Supplier<byte[]> file, String fileName, String contentType, int status, String code) {
        Session uploader = uploader();
        JsonNode before = uploadPng(uploader, "light", PhotoSteps.png(120, 40, 30));
        byte[] servedBefore = served(console(), before.path("url").asString("")).expectStatus(200).body();

        ApiResponse answer = upload(uploader, "light", fileName, contentType, file.get());

        assertThat(answer.status()).as("%s: %s", answer.request(), answer.snippet()).isEqualTo(status);
        JsonNode after = adminLogos(uploader).path("light");
        if (code == null) {
            assertThat(canonical(after)).as("the light logo of GET branding").isEqualTo(canonical(answer.json()));
            decodePng(served(console(), after.path("url").asString("")));
        } else {
            assertThat(answer.errorCode()).as("code of %s", answer.snippet()).isEqualTo(code);
            assertThat(canonical(after)).as("the light logo after the refusal").isEqualTo(canonical(before));
            assertThat(served(console(), before.path("url").asString("")).expectStatus(200).body())
                    .as("the served light logo after the refusal").isEqualTo(servedBefore);
        }
    }

    @ParameterizedTest(name = "theme {0}")
    @ValueSource(strings = {"blue", "LIGHT"})
    @DisplayName("S9-AC-17 uploading or deleting the logo of a theme other than light and dark is 404 NOT_FOUND and "
            + "the logos stay as they were")
    void unknownThemeIsNotFound(String theme) {
        Session uploader = uploader();
        uploadPng(uploader, "light", PhotoSteps.png(120, 40, 35));
        uploadPng(uploader, "dark", PhotoSteps.png(120, 40, 36));
        JsonNode before = adminLogos(uploader);

        ApiResponse uploaded = upload(uploader, theme, "logo.png", "image/png", PhotoSteps.png(120, 40, 37));
        ApiResponse deleted = uploader.client().delete(LOGOS + theme).send();

        assertRefused(uploaded, 404, "NOT_FOUND");
        assertRefused(deleted, 404, "NOT_FOUND");
        assertThat(canonical(adminLogos(uploader))).as("the logos after the 404s").isEqualTo(canonical(before));
    }
}
