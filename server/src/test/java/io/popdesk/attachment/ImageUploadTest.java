package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.MAX_FILE;
import static io.popdesk.attachment.AttachmentSteps.fieldCode;
import static io.popdesk.attachment.ImageSteps.image;
import static io.popdesk.attachment.ImageSteps.imagePath;
import static io.popdesk.attachment.ImageSteps.png;
import static io.popdesk.attachment.ImageSteps.read;
import static io.popdesk.attachment.ImageSteps.upload;
import static io.popdesk.attachment.ImageSteps.uploadRequest;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.AttachmentSteps.Scene;
import io.popdesk.attachment.ImageSteps.Format;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/**
 * Uploading a temporary image ({@code POST /api/{app}/images}): who gets an ID, which files are
 * images, the {@code Content-Type} the server decides, and the limits of one file. The limits of one
 * account are in {@link ImageLimitTest}, reading in {@link ImageReadTest}.
 */
class ImageUploadTest extends BareAppTest {

    private AttachmentSteps steps;
    private AttachmentRows rows;

    @BeforeEach
    void setUp() {
        steps = new AttachmentSteps(fixtures);
        rows = new AttachmentRows(db);
    }

    @Test
    @DisplayName("S5-AC-01, S5-AC-08 a customer on the portal and an agent on the console upload an image without "
            + "an issue and each get a UUID of its own, also for the same bytes")
    void uploadsOnBothAppsGiveUuids() {
        Scene s = steps.scene();
        Session agent = fixtures.agent(s.admin(), s.code());
        byte[] content = png();

        ApiResponse byCustomer = upload(s.customer(), "screen.png", content);
        ApiResponse again = upload(s.customer(), "screen.png", content);
        ApiResponse byAgent = upload(agent, "screen.png", content);

        List<String> ids = Stream.of(byCustomer, again, byAgent)
                .map(created -> created.expectStatus(201).jsonText("id")).toList();
        assertThat(ids).as("image IDs").allMatch(id -> ImageSteps.UUID_FORM.matcher(id).matches())
                .doesNotHaveDuplicates();
        assertThat(read(s.customer(), ids.get(0)).body()).as("the customer's image, read back").isEqualTo(content);
        assertThat(read(agent, ids.get(2)).body()).as("the agent's image, read back").isEqualTo(content);
        assertThat(rows.byId(ids.get(0)).state()).as("file state of a temporary image").isEqualTo("stored");
    }

    /** What the file part of an upload says about itself. */
    private record Claim(String fileName, String contentType) {}

    /** An image type that is not the format's own: what a client that trusts a wrong guess sends. */
    private static String anotherImageType(Format format) {
        return switch (format) {
            case PNG -> "image/gif";
            case JPEG -> "image/png";
            case GIF87A, GIF89A -> "image/webp";
            case WEBP -> "image/jpeg";
        };
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Format.class)
    @DisplayName("S5-AC-02, S5-AC-03 a PNG, JPEG, GIF, or WebP is stored whatever its name and the Content-Type sent "
            + "(text/html, the type of another image format, image/svg+xml), and is served with the Content-Type of "
            + "its format, before and after it is attached")
    void formatDecidesTheContentType(Format format) {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        Map<String, byte[]> stored = new LinkedHashMap<>();
        Map<String, String> sentAs = new LinkedHashMap<>();

        for (Claim claim : List.of(new Claim("page.html", "text/html"),
                new Claim("other." + format.extension(), anotherImageType(format)),
                new Claim("drawing.svg", "image/svg+xml"))) {
            byte[] content = image(format, Unique.number());
            ApiResponse created = uploadRequest(s.customer(), claim.fileName(), claim.contentType(), content).send();

            String id = created.expectStatus(201).jsonText("id");
            ApiResponse temporary = read(s.customer(), id);
            assertThat(temporary.status()).as(temporary.request()).isEqualTo(200);
            assertThat(temporary.header("Content-Type"))
                    .as("Content-Type of the temporary image sent as %s", claim.contentType())
                    .isEqualTo(format.contentType());
            assertThat(s.customer().client().head(imagePath(s.customer(), id)).send().header("Content-Type"))
                    .as("Content-Type of HEAD for the image sent as %s", claim.contentType())
                    .isEqualTo(format.contentType());
            stored.put(id, content);
            sentAs.put(id, claim.contentType());
        }

        ImageSteps.commentWith(s.customer(), issue.key(), "With " + format, stored.keySet());
        stored.forEach((id, content) -> {
            JsonNode listed = AttachmentSteps.entry(s.customer(), issue.key(), id);
            assertThat(listed).as("the image sent as %s in the issue's attachment list", sentAs.get(id)).isNotNull();
            assertThat(AttachmentSteps.text(listed, "contentType"))
                    .as("contentType in the list for the image sent as %s", sentAs.get(id))
                    .isEqualTo(format.contentType());
            ApiResponse attached = AttachmentSteps.download(s.customer(), issue.key(), id);
            assertThat(attached.status()).as(attached.request()).isEqualTo(200);
            assertThat(attached.header("Content-Type"))
                    .as("Content-Type of the attached image sent as %s", sentAs.get(id))
                    .isEqualTo(format.contentType());
            assertThat(attached.header("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(attached.body()).isEqualTo(content);
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Format.class)
    @DisplayName("S5-AC-02 a file that starts as an image and goes on as garbage is stored: the server reads the "
            + "first bytes only")
    void onlyTheFirstBytesAreRead(Format format) {
        Scene s = steps.scene();
        byte[] content = ImageSteps.brokenAfterSignature(format, Unique.number());

        ApiResponse created = upload(s.customer(), "broken." + format.extension(), content);

        String id = created.expectStatus(201).jsonText("id");
        ApiResponse back = read(s.customer(), id);
        assertThat(back.header("Content-Type")).isEqualTo(format.contentType());
        assertThat(back.body()).isEqualTo(content);
    }

    static Stream<Arguments> filesThatAreNoImages() {
        long n = Unique.number();
        return Stream.of(
                Arguments.of("an HTML page", "page.html", "text/html",
                        text("<!DOCTYPE html><html><script>alert(" + n + ")</script></html>")),
                Arguments.of("an SVG", "drawing.svg", "image/svg+xml",
                        text("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(" + n + ")</script></svg>")),
                Arguments.of("a PDF", "paper.pdf", "application/pdf", text("%PDF-1.7\n%popdesk " + n + "\n%%EOF")),
                Arguments.of("a RIFF file that is not WebP", "sound.webp", "image/webp",
                        text("RIFF\u0010\u0000\u0000\u0000WAVEfmt " + n)),
                Arguments.of("RIFF and nothing else", "short.webp", "image/webp", text("RIFF")),
                Arguments.of("text in a file named .png", "notes-" + n + ".png", "application/octet-stream",
                        text("Just notes, number " + n)),
                Arguments.of("HTML sent as image/png", "pixel.png", "image/png",
                        text("<html><body onload=alert(" + n + ")></body></html>")),
                Arguments.of("a GIF of a version there is not", "old.gif", "image/gif", text("GIF88a" + n)),
                Arguments.of("a BMP", "photo.bmp", "image/bmp", bmp(n)),
                Arguments.of("a TIFF, low byte first", "scan.tif", "image/tiff", tiff(ByteOrder.LITTLE_ENDIAN, n)),
                Arguments.of("a TIFF, high byte first, named .png and sent as image/png", "scan.png", "image/png",
                        tiff(ByteOrder.BIG_ENDIAN, n)),
                Arguments.of("an ICO", "favicon.ico", "image/x-icon", ico(n)));
    }

    // Images of formats the app does not take. Each is a one-pixel file followed by the digits of n.

    private static byte[] bmp(long n) {
        ByteBuffer b = ByteBuffer.allocate(58).order(ByteOrder.LITTLE_ENDIAN);
        b.put(text("BM")).putInt(58).putInt(0).putInt(54);
        b.putInt(40).putInt(1).putInt(1).putShort((short) 1).putShort((short) 24).putInt(0).putInt(4)
                .putInt(2835).putInt(2835).putInt(0).putInt(0);
        b.put(new byte[] {0, 0, (byte) 0xFF, 0});
        return withTail(b, n);
    }

    private static byte[] tiff(ByteOrder order, long n) {
        ByteBuffer b = ByteBuffer.allocate(111).order(order);
        b.put(text(order == ByteOrder.LITTLE_ENDIAN ? "II" : "MM")).putShort((short) 42).putInt(8);
        b.putShort((short) 8);
        tiffShort(b, 256, 1);
        tiffShort(b, 257, 1);
        tiffShort(b, 258, 8);
        tiffShort(b, 259, 1);
        tiffShort(b, 262, 1);
        tiffLong(b, 273, 110);
        tiffShort(b, 278, 1);
        tiffLong(b, 279, 1);
        b.putInt(0).put((byte) 0x80);
        return withTail(b, n);
    }

    private static void tiffShort(ByteBuffer b, int tag, int value) {
        b.putShort((short) tag).putShort((short) 3).putInt(1).putShort((short) value).putShort((short) 0);
    }

    private static void tiffLong(ByteBuffer b, int tag, int value) {
        b.putShort((short) tag).putShort((short) 4).putInt(1).putInt(value);
    }

    private static byte[] ico(long n) {
        ByteBuffer b = ByteBuffer.allocate(70).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0).putShort((short) 1).putShort((short) 1);
        b.put(new byte[] {1, 1, 0, 0}).putShort((short) 1).putShort((short) 32).putInt(48).putInt(22);
        b.putInt(40).putInt(1).putInt(2).putShort((short) 1).putShort((short) 32).putInt(0).putInt(0)
                .putInt(0).putInt(0).putInt(0).putInt(0);
        b.put(new byte[] {0, 0, (byte) 0xFF, (byte) 0xFF}).putInt(0);
        return withTail(b, n);
    }

    private static byte[] withTail(ByteBuffer full, long n) {
        if (full.hasRemaining()) {
            throw new IllegalStateException(full.remaining() + " bytes of the image are not written");
        }
        byte[] tail = text("#" + n);
        byte[] out = Arrays.copyOf(full.array(), full.capacity() + tail.length);
        System.arraycopy(tail, 0, out, full.capacity(), tail.length);
        return out;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("filesThatAreNoImages")
    @DisplayName("S5-AC-02 a file whose first bytes are not PNG, JPEG, GIF, or WebP is 400 FILE_NOT_IMAGE whatever "
            + "its name and the Content-Type sent, an image of another format too, and nothing of it is stored")
    void notAnImage(String what, String fileName, String contentType, byte[] content) {
        Scene s = steps.scene();
        // The format check is the only thing that differs from an upload that works.
        ImageSteps.uploaded(s.customer());

        ApiResponse refused = uploadRequest(s.customer(), fileName, contentType, content).send();

        assertThat(refused.status()).as("%s: %s", what, refused.snippet()).isEqualTo(400);
        assertThat(refused.errorCode()).as(what).isEqualTo("FILE_NOT_IMAGE");
        assertThat(rows.contentCopies(content)).as("stored copies of %s", what).isZero();
    }

    static Stream<Arguments> filesOutsideTheLimits() {
        return Stream.of(
                Arguments.of("0 bytes", "empty.png", new byte[0], "FILE_EMPTY", null),
                Arguments.of("10 MB and one byte", "over.png", image(Format.PNG, MAX_FILE + 1, 21), "FILE_TOO_LARGE",
                        null),
                Arguments.of("no file name", "", image(Format.PNG, 22), "INVALID_INPUT", "REQUIRED"),
                Arguments.of("a name of 256 characters", "n".repeat(252) + ".png", image(Format.PNG, 23),
                        "INVALID_INPUT", "TOO_LONG"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("filesOutsideTheLimits")
    @DisplayName("S5-AC-04 an image of 0 bytes, over 10 MB, or with a name that is not 1 to 255 characters gets the "
            + "400 an attachment upload gets, and a name of 255 characters is stored")
    void fileLimitsOfAnAttachment(String what, String fileName, byte[] content, String code, String fieldCode) {
        Scene s = steps.scene();
        String longest = "n".repeat(251) + ".png";

        ApiResponse stored = upload(s.customer(), longest, png());
        ApiResponse refused = upload(s.customer(), fileName, content);

        assertThat(stored.status()).as("a name of 255 characters: %s", stored.snippet()).isEqualTo(201);
        assertThat(refused.status()).as("%s: %s", what, refused.snippet()).isEqualTo(400);
        assertThat(refused.errorCode()).as(what).isEqualTo(code);
        assertThat(fieldCode(refused, "file")).as("code of the field file for %s", what).isEqualTo(fieldCode);
    }

    private static byte[] text(String content) {
        return content.getBytes(StandardCharsets.ISO_8859_1);
    }
}
