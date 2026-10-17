package io.popdesk.branding;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.photo.PhotoSteps;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Session;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import tools.jackson.databind.JsonNode;

/**
 * What the logo tests of slice 9 share (S9-AC-03, 04, 15~19, 21): the upload and the public logo address, the
 * fingerprint, and test images the photo steps ({@link PhotoSteps}) do not have. The context is the one of
 * {@link BrandingAppTest}.
 *
 * <p>An admin may upload 10 logos a minute and the app clock stands still, so a test uploads with an admin of its
 * own ({@link #uploader}); the first admin only deletes the logos at the start.
 */
abstract class BrandingLogoAppTest extends BrandingAppTest {

    /** The largest logo file: 512 KB counted as 524,288 bytes. */
    static final int MAX_BYTES = 512 * 1024;

    /** The stored logo fits a box this wide and this high. */
    static final int BOX_WIDTH = 512;

    static final int BOX_HEIGHT = 128;

    /** The start of a public logo address, before the theme. */
    static final String PUBLIC_LOGOS = "/api/public/branding/logos/";

    private static final Pattern ADDRESS = Pattern.compile("/api/public/branding/logos/(light|dark)\\?v=([^&]+)");

    /** The chunks a PNG without metadata may have. */
    static final List<String> PLAIN_CHUNKS = List.of("IHDR", "PLTE", "tRNS", "IDAT", "IEND");

    /** A new admin, made by the first admin, who has uploaded nothing yet. */
    Session uploader() {
        return fixtures.admin(fixtures.signInAdmin());
    }

    /** The first admin deletes both logos, so the test starts without one. */
    void startWithoutLogos() {
        deleteLogos(fixtures.signInAdmin());
    }

    /** {@code PUT /api/console/branding/logos/{theme}} with the file in the part {@code file}, whatever the answer. */
    static ApiResponse upload(Session admin, String theme, String fileName, String contentType, byte[] content) {
        return admin.client().put(LOGOS + theme).file("file", fileName, contentType, content).send();
    }

    /** Uploads a PNG and expects 200; gives the {@code BrandingLogo} of the answer. */
    static JsonNode uploadPng(Session admin, String theme, byte[] png) {
        return upload(admin, theme, "logo.png", "image/png", png).expectStatus(200).json();
    }

    /** The {@code BRANDING_LOGO_CHANGED} entries whose actor is {@code admin}, newest first. */
    static List<JsonNode> logoEntries(Session admin) {
        ApiResponse page = admin.client().get("/api/console/audit-log").query("actorAccountId", admin.accountId())
                .query("action", "BRANDING_LOGO_CHANGED").query("size", 100).expect(200);
        JsonNode items = page.json().path("items");
        if (!items.isArray()) {
            throw new AssertionError(page.request() + ": no items array in " + page.snippet());
        }
        return List.copyOf(items.values());
    }

    /** The {@code logos} of the admin's {@code GET /api/console/branding}. */
    static JsonNode adminLogos(Session admin) {
        return branding(admin).path("logos");
    }

    /** The {@code logos} of the public branding, read without a session on {@code client}. */
    static JsonNode publicLogos(ApiClient client) {
        return publicBranding(client).path("logos");
    }

    /** {@code GET} of a logo address (path and query) on {@code client}, whatever the answer. */
    static ApiResponse served(ApiClient client, String address) {
        return client.get(address).send();
    }

    /** The {@code v} of a public logo address of {@code theme}; fails unless it is one. */
    static String versionOf(String address, String theme) {
        assertThat(address).as("logo address").isNotNull();
        Matcher m = ADDRESS.matcher(address);
        assertThat(m.matches()).as("%s is a public logo address", address).isTrue();
        assertThat(m.group(1)).as("theme of %s", address).isEqualTo(theme);
        return m.group(2);
    }

    /** The first 16 characters of the SHA-256 of a served logo's body, as lower-case hex. */
    static String fingerprint(ApiResponse served) {
        return served.sha256().substring(0, 16);
    }

    /** The types of a PNG's chunks, in order. */
    static List<String> chunks(byte[] png) {
        List<String> out = new ArrayList<>();
        ByteBuffer buffer = ByteBuffer.wrap(png);
        int at = 8;
        while (at + 8 <= png.length) {
            int length = buffer.getInt(at);
            out.add(new String(png, at + 4, 4, StandardCharsets.ISO_8859_1));
            if (length < 0) {
                break;
            }
            at += 12 + length;
        }
        return out;
    }

    /** An SVG drawing, which is text. */
    static byte[] svg() {
        return ("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"200\" height=\"50\">"
                + "<rect width=\"200\" height=\"50\" fill=\"#6D28D9\"/><script>alert(1)</script></svg>\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** An HTML page, to send under a PNG name. */
    static byte[] html() {
        return "<!doctype html><html><body><script>alert(document.cookie)</script></body></html>\n"
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A JPEG of {@code width} by {@code height}, red on its left half and blue on its right half, with an EXIF
     * segment that names {@code orientation} (6: "turn 90 degrees clockwise to show", so the left side is the top).
     */
    static byte[] jpegLeftRedRightBlue(int width, int height, int orientation) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, width / 2, height);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(width / 2, 0, width - width / 2, height);
        graphics.dispose();
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "jpg", plain);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        byte[] jpeg = plain.toByteArray();

        // Big-endian TIFF with one entry: Orientation (274), SHORT, count 1.
        ByteBuffer tiff = ByteBuffer.allocate(8 + 2 + 12 + 4).order(ByteOrder.BIG_ENDIAN);
        tiff.put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(8);
        tiff.putShort((short) 1);
        tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        tiff.putInt(0);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        int length = 2 + 6 + tiff.capacity();
        out.write(0xFF);
        out.write(0xE1);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.writeBytes("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(tiff.array());
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    static boolean red(int rgb) {
        return (rgb >> 16 & 0xFF) > 180 && (rgb & 0xFF) < 80;
    }

    static boolean blue(int rgb) {
        return (rgb & 0xFF) > 180 && (rgb >> 16 & 0xFF) < 80;
    }
}
