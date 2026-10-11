package io.popdesk.photo;

import static io.popdesk.photo.PhotoSteps.MAX_BYTES;
import static io.popdesk.photo.PhotoSteps.assertScaled;
import static io.popdesk.photo.PhotoSteps.contains;
import static io.popdesk.photo.PhotoSteps.decodePng;
import static io.popdesk.photo.PhotoSteps.photo;
import static io.popdesk.photo.PhotoSteps.photoUpdatedAt;
import static io.popdesk.photo.PhotoSteps.upload;
import static io.popdesk.photo.PhotoSteps.uploadPng;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.DbInspector.Column;
import io.popdesk.support.Fixtures.App;
import io.popdesk.support.Fixtures.Session;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Uploading, replacing, and deleting one's own profile photo (ACCT-8, ADR D-52): what is stored is a
 * PNG that fits 256 px, made again from the pixels, whatever was sent.
 *
 * <p>Uploads are limited to 10 per account in 60 seconds and the test clock stands still, so every
 * test uses an account of its own.
 */
class PhotoUploadTest extends BareAppTest {

    private Session customer() {
        Session admin = fixtures.signInAdmin();
        return fixtures.customer(admin, fixtures.createCompany(admin).code());
    }

    private Session person(App app) {
        return app == App.PORTAL ? customer() : fixtures.agent(fixtures.signInAdmin());
    }

    private static void assertNoPhoto(Session session) {
        assertThat(photoUpdatedAt(session).isNull()).as("photoUpdatedAt of %s is null", session.email()).isTrue();
        ApiResponse own = photo(session, session.accountId());
        assertThat(own.status()).as("%s: %s", own, own.snippet()).isEqualTo(404);
        assertThat(own.errorCode()).isEqualTo("NOT_FOUND");
    }

    static Stream<Arguments> formats() {
        return Stream.of(
                arguments(named("PNG 640x480 on the portal", (Supplier<byte[]>) () -> PhotoSteps.png(640, 480, 1)),
                        "photo.png", "image/png", 640, 480, App.PORTAL),
                arguments(named("JPEG 480x640 on the console", (Supplier<byte[]>) () -> PhotoSteps.jpeg(480, 640, 2)),
                        "photo.jpg", "image/jpeg", 480, 640, App.CONSOLE),
                arguments(named("lossy WebP 640x480 on the portal",
                                (Supplier<byte[]>) () -> PhotoSteps.resource("lossy-640x480.webp")),
                        "photo.webp", "image/webp", 640, 480, App.PORTAL),
                arguments(named("lossless WebP with alpha 300x600 on the console",
                                (Supplier<byte[]>) () -> PhotoSteps.resource("lossless-alpha-300x600.webp")),
                        "photo.webp", "image/webp", 300, 600, App.CONSOLE),
                arguments(named("PNG 100x50, already inside 256 px", (Supplier<byte[]>) () -> PhotoSteps.png(100, 50, 3)),
                        "small.png", "image/png", 100, 50, App.PORTAL));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("formats")
    @DisplayName("S1A-AC-20 an uploaded PNG, JPEG, or WebP is answered with 201 Me and served as a PNG that fits "
            + "256 px with the ratio kept")
    void storesAScaledPng(Supplier<byte[]> image, String fileName, String contentType, int width, int height,
            App app) {
        Session session = person(app);
        assertNoPhoto(session);

        ApiResponse created = upload(session, fileName, contentType, image.get());

        assertThat(created.status()).as("%s: %s", created, created.snippet()).isEqualTo(201);
        assertThat(created.jsonLong("id")).as("id of the Me in %s", created.snippet()).isEqualTo(session.accountId());
        String changedAt = created.jsonText("photoUpdatedAt");
        assertThat(OffsetDateTime.parse(changedAt).toInstant()).as("photoUpdatedAt, the app's time of the upload")
                .isEqualTo(clock.instant());
        assertThat(photoUpdatedAt(session).asString(null)).as("photoUpdatedAt of GET me").isEqualTo(changedAt);
        assertScaled(decodePng(photo(session, session.accountId())), width, height);
    }

    @Test
    @DisplayName("S1A-AC-20 a second upload replaces the first: photoUpdatedAt moves on and the served photo is "
            + "the new one")
    void secondUploadReplacesTheFirst() {
        Session session = customer();
        String firstAt = uploadPng(session, PhotoSteps.png(400, 300, 4));
        byte[] first = photo(session, session.accountId()).expectStatus(200).body();
        clock.advance(Duration.ofSeconds(2));

        String secondAt = uploadPng(session, PhotoSteps.png(300, 400, 5));

        assertThat(OffsetDateTime.parse(secondAt)).as("photoUpdatedAt after the second upload")
                .isAfter(OffsetDateTime.parse(firstAt));
        assertThat(photoUpdatedAt(session).asString(null)).isEqualTo(secondAt);
        ApiResponse served = photo(session, session.accountId());
        assertThat(served.body()).as("the served photo after the second upload").isNotEqualTo(first);
        assertScaled(decodePng(served), 300, 400);
    }

    static Stream<Arguments> withMetadata() {
        return Stream.of(
                arguments(named("JPEG with EXIF", (Supplier<byte[]>) () -> PhotoSteps.jpegWithExif(640, 480,
                        "popdesk-exif-marker-jpeg 37.5665N 126.9780E")), "camera.jpg",
                        "popdesk-exif-marker-jpeg"),
                arguments(named("PNG with tEXt and eXIf chunks", (Supplier<byte[]>) () -> PhotoSteps.pngWithMetadata(
                        640, 480, "popdesk-exif-marker-png 37.5665N 126.9780E")), "screenshot.png",
                        "popdesk-exif-marker-png"),
                arguments(named("WebP with an EXIF chunk",
                                (Supplier<byte[]>) () -> PhotoSteps.resource("exif-320x240.webp")),
                        "phone.webp", "popdesk-exif-marker-webp"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("withMetadata")
    @DisplayName("S1A-AC-21 the served photo holds no EXIF or text of the original, and no bytea column of the "
            + "database holds the original's bytes")
    void keepsNoMetadataOrOriginalBytes(Supplier<byte[]> image, String fileName, String marker) {
        Session session = customer();
        byte[] original = image.get();
        byte[] markerBytes = marker.getBytes(StandardCharsets.ISO_8859_1);
        assertThat(contains(original, markerBytes)).as("the test image holds its marker").isTrue();

        upload(session, fileName, "application/octet-stream", original).expectStatus(201);

        ApiResponse served = photo(session, session.accountId());
        decodePng(served);
        assertThat(contains(served.body(), markerBytes)).as("the marker text in the served photo").isFalse();
        for (String sign : List.of("Exif", "eXIf", "EXIF", "tEXt", "iTXt", "zTXt")) {
            assertThat(contains(served.body(), sign.getBytes(StandardCharsets.ISO_8859_1)))
                    .as("'%s' in the served photo", sign).isFalse();
        }

        List<String> holdingStored = new ArrayList<>();
        List<String> holdingOriginal = new ArrayList<>();
        for (String table : db.tables()) {
            for (Column column : db.columns(table)) {
                if (!column.dataType().equals("bytea")) {
                    continue;
                }
                String cell = quote(table) + "." + quote(column.name());
                String from = "select count(*) from " + quote(table) + " where ";
                if (db.count(from + quote(column.name()) + " = ?", (Object) served.body()) > 0) {
                    holdingStored.add(cell);
                }
                if (db.count(from + "position(? in " + quote(column.name()) + ") > 0", (Object) markerBytes) > 0
                        || db.count(from + quote(column.name()) + " = ?", (Object) original) > 0) {
                    holdingOriginal.add(cell);
                }
            }
        }
        assertThat(holdingStored).as("bytea columns that hold the served photo (the search looks where photos are)")
                .isNotEmpty();
        assertThat(holdingOriginal).as("bytea columns that hold the original or its marker").isEmpty();
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    static Stream<Arguments> sizesAndContents() {
        byte[] text = "This is a text file, whatever its name says.\n".repeat(40).getBytes(StandardCharsets.UTF_8);
        return Stream.of(
                arguments(named("a PNG of exactly 2,097,152 bytes",
                        (Supplier<byte[]>) () -> PhotoSteps.pngOfSize(MAX_BYTES, 6)), "photo.png", "image/png", 201, null),
                arguments(named("a PNG of 2,097,153 bytes",
                                (Supplier<byte[]>) () -> PhotoSteps.pngOfSize(MAX_BYTES + 1, 7)),
                        "photo.png", "image/png", 413, "PAYLOAD_TOO_LARGE"),
                arguments(named("a text file named photo.png and sent as image/png", (Supplier<byte[]>) () -> text),
                        "photo.png", "image/png", 400, "UNSUPPORTED_IMAGE"),
                arguments(named("a GIF named photo.png and sent as image/png",
                                (Supplier<byte[]>) () -> PhotoSteps.gif(200, 100, 8)),
                        "photo.png", "image/png", 400, "UNSUPPORTED_IMAGE"),
                arguments(named("the first bytes of a PNG followed by noise", (Supplier<byte[]>) () -> {
                    byte[] broken = PhotoSteps.png(200, 100, 9);
                    Arrays.fill(broken, 8, broken.length, (byte) 0x5A);
                    return broken;
                }), "photo.png", "image/png", 400, "UNSUPPORTED_IMAGE"),
                arguments(named("a PNG named notes.txt and sent as text/plain",
                                (Supplier<byte[]>) () -> PhotoSteps.png(200, 100, 10)),
                        "notes.txt", "text/plain", 201, null),
                arguments(named("a JPEG named photo.png and sent as image/png",
                                (Supplier<byte[]>) () -> PhotoSteps.jpeg(200, 100, 11)),
                        "photo.png", "image/png", 201, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sizesAndContents")
    @DisplayName("S1A-AC-22 2 MB is taken and one byte more is 413; what is not a PNG, JPEG, or WebP by its content "
            + "is 400 UNSUPPORTED_IMAGE whatever its name and Content-Type say; a refused file stores nothing")
    void judgedBySizeAndContent(Supplier<byte[]> file, String fileName, String contentType, int status, String code) {
        Session session = customer();

        ApiResponse response = upload(session, fileName, contentType, file.get());

        assertThat(response.status()).as("%s: %s", response, response.snippet()).isEqualTo(status);
        if (code == null) {
            decodePng(photo(session, session.accountId()));
            assertThat(photoUpdatedAt(session).isString()).as("photoUpdatedAt after the upload").isTrue();
        } else {
            assertThat(response.errorCode()).isEqualTo(code);
            assertNoPhoto(session);
        }
    }

    static Stream<Arguments> pixelCounts() {
        return Stream.of(
                arguments(named("PNG 5000x5000, 25,000,000 pixels",
                        (Supplier<byte[]>) () -> PhotoSteps.solidPng(5000, 5000)), 5000, 5000, true),
                arguments(named("PNG 5001x5000", (Supplier<byte[]>) () -> PhotoSteps.solidPng(5001, 5000)),
                        5001, 5000, false),
                arguments(named("JPEG 5000x5000, 25,000,000 pixels",
                        (Supplier<byte[]>) () -> PhotoSteps.solidJpeg(5000, 5000)), 5000, 5000, true),
                arguments(named("JPEG 5000x5001", (Supplier<byte[]>) () -> PhotoSteps.solidJpeg(5000, 5001)),
                        5000, 5001, false),
                arguments(named("WebP 4000x3000, 12,000,000 pixels",
                        (Supplier<byte[]>) () -> PhotoSteps.resource("solid-4000x3000.webp")), 4000, 3000, true),
                arguments(named("WebP 4001x3000", (Supplier<byte[]>) () -> PhotoSteps.resource("solid-4001x3000.webp")),
                        4001, 3000, false),
                arguments(named("a PNG whose header says 30000x30000, with no pixel data",
                        (Supplier<byte[]>) () -> PhotoSteps.pngHeaderOnly(30000, 30000)), 30000, 30000, false),
                arguments(named("a WebP whose header says 16383x16383, with no pixel data",
                        (Supplier<byte[]>) () -> PhotoSteps.webpHeaderOnly(16383, 16383)), 16383, 16383, false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pixelCounts")
    @DisplayName("S1A-AC-23 a PNG or JPEG of 25 million pixels and a WebP of 12 million are taken; one row or "
            + "column more is 400 IMAGE_TOO_LARGE, also when only the header says so, and stores nothing")
    void pixelLimit(Supplier<byte[]> image, int width, int height, boolean taken) {
        Session session = customer();
        byte[] content = image.get();
        assertThat(content.length).as("size of the test image, under the 2 MB limit").isLessThanOrEqualTo(MAX_BYTES);

        ApiResponse response = upload(session, "large", "application/octet-stream", content);

        if (taken) {
            assertThat(response.status()).as("%s: %s", response, response.snippet()).isEqualTo(201);
            assertScaled(decodePng(photo(session, session.accountId())), width, height);
        } else {
            assertThat(response.status()).as("%s: %s", response, response.snippet()).isEqualTo(400);
            assertThat(response.errorCode()).isEqualTo("IMAGE_TOO_LARGE");
            assertNoPhoto(session);
        }
    }

    @Test
    @DisplayName("S1A-AC-24 deleting one's photo is 204, the photo is gone and Me says null; deleting again is 204")
    void deleteRemovesThePhoto() {
        Session session = person(App.CONSOLE);
        uploadPng(session, PhotoSteps.png(300, 300, 12));
        photo(session, session.accountId()).expectStatus(200);

        ApiResponse deleted = session.client().delete(session.api("/me/photo")).send();

        assertThat(deleted.status()).as("%s: %s", deleted, deleted.snippet()).isEqualTo(204);
        assertNoPhoto(session);
        ApiResponse again = session.client().delete(session.api("/me/photo")).send();
        assertThat(again.status()).as("%s: %s", again, again.snippet()).isEqualTo(204);
    }

    @Test
    @DisplayName("S1A-AC-28 the 11th upload of one account within 60 seconds is 429 RATE_LIMITED with Retry-After "
            + "and changes nothing; another account is not limited, and 60 seconds later the account uploads again")
    void eleventhUploadInAMinuteIsLimited() {
        Session session = customer();
        Session other = customer();
        for (int i = 0; i < 10; i++) {
            ApiResponse created = upload(session, "photo-" + i + ".png", "image/png", PhotoSteps.png(64, 64, 100 + i));
            assertThat(created.status()).as("upload %d: %s", i + 1, created.snippet()).isEqualTo(201);
        }
        byte[] tenth = photo(session, session.accountId()).expectStatus(200).body();

        ApiResponse eleventh = upload(session, "photo-10.png", "image/png", PhotoSteps.png(64, 64, 200));

        assertThat(eleventh.status()).as("%s: %s", eleventh, eleventh.snippet()).isEqualTo(429);
        assertThat(eleventh.errorCode()).isEqualTo("RATE_LIMITED");
        assertThat(eleventh.header("Retry-After")).as("Retry-After").isNotNull().matches("[1-9][0-9]*");
        assertThat(Integer.parseInt(eleventh.header("Retry-After"))).as("Retry-After in seconds").isBetween(1, 60);
        assertThat(photo(session, session.accountId()).expectStatus(200).body()).as("the photo after the 429")
                .isEqualTo(tenth);
        assertThat(upload(other, "photo.png", "image/png", PhotoSteps.png(64, 64, 201)).status())
                .as("upload of another account").isEqualTo(201);

        clock.advance(Duration.ofSeconds(61));
        ApiResponse afterWindow = upload(session, "photo-11.png", "image/png", PhotoSteps.png(64, 64, 202));

        assertThat(afterWindow.status()).as("%s: %s", afterWindow, afterWindow.snippet()).isEqualTo(201);
    }
}
