package io.popdesk.branding;

import static io.popdesk.photo.PhotoSteps.contains;
import static io.popdesk.photo.PhotoSteps.decodePng;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.popdesk.photo.PhotoSteps;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Session;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/**
 * Uploading a logo (S9-AC-15, BRAND-2, D-52): what is stored is a PNG made again from the pixels, turned by the EXIF
 * orientation, that fits 512 by 128 px without being made larger, and holds no metadata and not the bytes sent.
 */
class BrandingLogoUploadTest extends BrandingLogoAppTest {

    /** The stored logo's width and height, as the box gives them for an image of {@code width} by {@code height}. */
    private static void assertFitsTheBox(BufferedImage stored, int width, int height) {
        double scale = Math.min(1.0, Math.min((double) BOX_WIDTH / width, (double) BOX_HEIGHT / height));
        String what = stored.getWidth() + "x" + stored.getHeight() + " from " + width + "x" + height;
        assertThat(stored.getWidth()).as("width of %s", what).isLessThanOrEqualTo(BOX_WIDTH)
                .isCloseTo((int) Math.round(width * scale), offset(1));
        assertThat(stored.getHeight()).as("height of %s", what).isLessThanOrEqualTo(BOX_HEIGHT)
                .isCloseTo((int) Math.round(height * scale), offset(1));
    }

    /** The served logo of {@code theme}: its address is the answer's {@code url}, and its size the answer's. */
    private ApiResponse servedAs(JsonNode answer, String theme) {
        String url = answer.path("url").asString(null);
        versionOf(url, theme);
        ApiResponse served = served(portal(), url);
        BufferedImage image = decodePng(served);
        assertThat(answer.path("width").asInt(-1)).as("width of the answer %s", answer).isEqualTo(image.getWidth());
        assertThat(answer.path("height").asInt(-1)).as("height of the answer %s", answer).isEqualTo(image.getHeight());
        return served;
    }

    static Stream<Arguments> formats() {
        return Stream.of(
                arguments(named("PNG 1024x64, wider than the box", (Supplier<byte[]>) () -> PhotoSteps.png(1024, 64, 1)),
                        "logo.png", "image/png", 1024, 64, "light"),
                arguments(named("JPEG 300x600, higher than the box",
                                (Supplier<byte[]>) () -> PhotoSteps.jpeg(300, 600, 2)),
                        "logo.jpg", "image/jpeg", 300, 600, "dark"),
                arguments(named("lossy WebP 640x480", (Supplier<byte[]>) () -> PhotoSteps.resource("lossy-640x480.webp")),
                        "logo.webp", "image/webp", 640, 480, "light"),
                arguments(named("lossless WebP with alpha 300x600",
                                (Supplier<byte[]>) () -> PhotoSteps.resource("lossless-alpha-300x600.webp")),
                        "logo.webp", "image/webp", 300, 600, "dark"),
                arguments(named("PNG 100x50, already inside the box and not made larger",
                                (Supplier<byte[]>) () -> PhotoSteps.png(100, 50, 3)),
                        "small.png", "image/png", 100, 50, "light"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("formats")
    @DisplayName("S9-AC-15 an uploaded PNG, JPEG, or WebP is answered with 200 BrandingLogo (url, width, height) and "
            + "served as a PNG that fits 512x128 with the ratio kept, never made larger, and not the bytes sent")
    void storesAPngInTheBox(Supplier<byte[]> image, String fileName, String contentType, int width, int height,
            String theme) {
        Session uploader = uploader();
        byte[] original = image.get();

        ApiResponse answer = upload(uploader, theme, fileName, contentType, original);

        assertThat(answer.status()).as("%s: %s", answer.request(), answer.snippet()).isEqualTo(200);
        assertThat(keys(answer.json())).as("fields of the answer").containsExactlyInAnyOrder("url", "width", "height");
        ApiResponse served = servedAs(answer.json(), theme);
        assertFitsTheBox(decodePng(served), width, height);
        assertThat(served.body()).as("the served logo").isNotEqualTo(original);
        assertThat(canonical(adminLogos(uploader).path(theme))).as("the %s logo of GET branding", theme)
                .isEqualTo(canonical(answer.json()));
    }

    @Test
    @DisplayName("S9-AC-15 a JPEG whose EXIF says 'turn 90 degrees clockwise' is turned first and then fitted: "
            + "200x1024 in the file is stored 512x100 with its left side, red, at the top")
    void turnedBeforeItIsFitted() {
        Session uploader = uploader();

        ApiResponse answer = upload(uploader, "light", "phone.jpg", "image/jpeg", jpegLeftRedRightBlue(200, 1024, 6));

        assertThat(answer.status()).as("%s: %s", answer.request(), answer.snippet()).isEqualTo(200);
        BufferedImage stored = decodePng(servedAs(answer.json(), "light"));
        assertThat(stored.getWidth()).as("width of the stored logo").isEqualTo(512);
        assertThat(stored.getHeight()).as("height of the stored logo").isEqualTo(100);
        assertThat(red(stored.getRGB(256, 10))).as("the top of the stored logo is the original's left, red").isTrue();
        assertThat(blue(stored.getRGB(256, 90))).as("the bottom of the stored logo is the original's right, blue")
                .isTrue();
    }

    static Stream<Arguments> withMetadata() {
        return Stream.of(
                arguments(named("JPEG with EXIF", (Supplier<byte[]>) () -> PhotoSteps.jpegWithExif(320, 240,
                        "popdesk-exif-marker-jpeg 37.5665N 126.9780E")), "camera.jpg", "popdesk-exif-marker-jpeg"),
                arguments(named("PNG with tEXt and eXIf chunks", (Supplier<byte[]>) () -> PhotoSteps.pngWithMetadata(
                        320, 240, "popdesk-exif-marker-png 37.5665N 126.9780E")), "logo.png", "popdesk-exif-marker-png"),
                arguments(named("WebP with an EXIF chunk",
                                (Supplier<byte[]>) () -> PhotoSteps.resource("exif-320x240.webp")),
                        "logo.webp", "popdesk-exif-marker-webp"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("withMetadata")
    @DisplayName("S9-AC-15 the served logo is a PNG of plain chunks without the original's EXIF or text, and the "
            + "logo table holds the served PNG and not the original's bytes")
    void keepsNoMetadataOrOriginalBytes(Supplier<byte[]> image, String fileName, String marker) {
        Session uploader = uploader();
        byte[] original = image.get();
        byte[] markerBytes = marker.getBytes(StandardCharsets.ISO_8859_1);
        assertThat(contains(original, markerBytes)).as("the test image holds its marker").isTrue();

        ApiResponse answer = upload(uploader, "dark", fileName, "application/octet-stream", original);

        assertThat(answer.status()).as("%s: %s", answer.request(), answer.snippet()).isEqualTo(200);
        ApiResponse served = servedAs(answer.json(), "dark");
        assertThat(contains(served.body(), markerBytes)).as("the marker text in the served logo").isFalse();
        assertThat(chunks(served.body())).as("chunks of the served logo").isSubsetOf(PLAIN_CHUNKS)
                .contains("IHDR", "IDAT", "IEND");
        assertThat(db.count("select count(*) from branding_logo where theme = 'DARK' and png = ?",
                (Object) served.body())).as("the dark row holds the served PNG").isEqualTo(1);
        assertThat(db.count("select count(*) from branding_logo where png = ? or position(? in png) > 0",
                original, markerBytes)).as("logo rows that hold the original or its marker").isZero();
    }

    @Test
    @DisplayName("S9-AC-15 an upload without the part file is 400 INVALID_INPUT with file REQUIRED and stores "
            + "nothing")
    void fileIsRequired() {
        Session uploader = uploader();
        JsonNode before = uploadPng(uploader, "light", PhotoSteps.png(64, 32, 4));

        ApiResponse refused = uploader.client().put(LOGOS + "light").part("note", "no file here").send();

        assertInvalid(refused, "file", "REQUIRED");
        assertThat(canonical(adminLogos(uploader).path("light"))).as("the light logo after the 400")
                .isEqualTo(canonical(before));
    }
}
