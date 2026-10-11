package io.popdesk.photo;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Session;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import tools.jackson.databind.JsonNode;

/**
 * Profile photo steps and test images. The requests go through a signed-in {@link Session}; the
 * images are made here ({@code BufferedImage} written by {@code ImageIO}) or, for WebP, read from
 * {@code src/test/resources/photo} (the JDK cannot write WebP; that folder's README says how the
 * files were made). Nothing is taken from outside.
 *
 * <p>Public, so the boundary and deploy tests use the same images and do not copy them.
 */
public final class PhotoSteps {

    /** The largest photo file: 2 MB counted as 2,097,152 bytes. */
    public static final int MAX_BYTES = 2 * 1024 * 1024;

    /** The stored photo fits a square of this many pixels. */
    public static final int MAX_SIDE = 256;

    /** An account ID no account has. */
    public static final long NO_ACCOUNT = 999_999_999_999L;

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    /** Where the first chunk after IHDR starts: the signature and the 25 bytes of the IHDR chunk. */
    private static final int AFTER_IHDR = 8 + 25;

    private PhotoSteps() {}

    // Requests

    /** {@code POST /api/{app}/me/photo} with the file in the part {@code file}, whatever the answer. */
    public static ApiResponse upload(Session session, String fileName, String contentType, byte[] content) {
        return session.client().post(session.api("/me/photo")).file("file", fileName, contentType, content).send();
    }

    /** Uploads a PNG and expects 201; returns {@code photoUpdatedAt} of the answer. */
    public static String uploadPng(Session session, byte[] png) {
        ApiResponse created = upload(session, "photo.png", "image/png", png).expectStatus(201);
        JsonNode at = created.json().path("photoUpdatedAt");
        assertThat(at.isString()).as("photoUpdatedAt of %s: %s", created, created.snippet()).isTrue();
        return at.stringValue();
    }

    /** {@code GET /api/{app}/photos/{accountId}} as {@code viewer}, whatever the answer. */
    public static ApiResponse photo(Session viewer, long accountId) {
        return viewer.client().get(viewer.api("/photos/" + accountId)).send();
    }

    /** {@code photoUpdatedAt} of the caller's {@code GET /api/{app}/me}: a JSON string or JSON null. */
    public static JsonNode photoUpdatedAt(Session session) {
        ApiResponse me = session.client().get(session.api("/me")).expect(200);
        assertThat(me.json().has("photoUpdatedAt")).as("photoUpdatedAt in %s", me.snippet()).isTrue();
        return me.json().path("photoUpdatedAt");
    }

    /** The photo an answer carries: 200, {@code image/png}, a PNG by its first bytes, and readable. */
    public static BufferedImage decodePng(ApiResponse response) {
        response.expectStatus(200);
        assertThat(response.header("Content-Type")).as("Content-Type of %s", response).isEqualTo("image/png");
        byte[] body = response.body();
        assertThat(body.length).as("size of the photo from %s", response).isGreaterThan(PNG_SIGNATURE.length);
        assertThat(Arrays.copyOf(body, PNG_SIGNATURE.length)).as("first bytes of the photo from %s", response)
                .isEqualTo(PNG_SIGNATURE);
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(body));
        } catch (IOException e) {
            throw new AssertionError("The photo from " + response + " is not a readable PNG: " + e, e);
        }
        assertThat(image).as("the photo from %s, read as an image", response).isNotNull();
        return image;
    }

    /**
     * Checks that a stored photo of an original {@code width} by {@code height} fits 256 px and keeps
     * the ratio: an original larger than 256 px has its long side at exactly 256.
     */
    public static void assertScaled(BufferedImage stored, int width, int height) {
        int longSide = Math.max(stored.getWidth(), stored.getHeight());
        String what = stored.getWidth() + "x" + stored.getHeight() + " from " + width + "x" + height;
        if (Math.max(width, height) > MAX_SIDE) {
            assertThat(longSide).as("long side of %s", what).isEqualTo(MAX_SIDE);
        } else {
            assertThat(longSide).as("long side of %s", what).isLessThanOrEqualTo(MAX_SIDE);
        }
        assertThat(Math.abs((long) stored.getWidth() * height - (long) stored.getHeight() * width))
                .as("ratio of %s, off by at most one pixel", what)
                .isLessThanOrEqualTo(Math.max(width, height));
    }

    /**
     * Checks that {@code actual} is the 404 the same caller gets for an account that does not exist:
     * status, body, and every header but {@code Date}.
     */
    public static void assertLikeNoAccount(ApiResponse actual, Session caller) {
        ApiResponse unknown = photo(caller, NO_ACCOUNT);
        assertThat(unknown.status()).as("%s: %s", unknown, unknown.snippet()).isEqualTo(404);
        assertThat(unknown.errorCode()).isEqualTo("NOT_FOUND");
        assertThat(actual.status()).as("%s: %s", actual, actual.snippet()).isEqualTo(404);
        assertThat(actual.text()).as("body of %s", actual).isEqualTo(unknown.text());
        assertThat(headersWithoutDate(actual)).as("headers of %s", actual).isEqualTo(headersWithoutDate(unknown));
    }

    private static Map<String, List<String>> headersWithoutDate(ApiResponse response) {
        Map<String, List<String>> out = new TreeMap<>();
        response.headers().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("date")) {
                out.put(name.toLowerCase(Locale.ROOT), values);
            }
        });
        return out;
    }

    /** The directives of a {@code Cache-Control} header, lower case, sorted. */
    public static List<String> cacheControl(ApiResponse response) {
        String header = response.header("Cache-Control");
        assertThat(header).as("Cache-Control of %s", response).isNotNull();
        return Arrays.stream(header.split(",")).map(d -> d.strip().toLowerCase(Locale.ROOT)).sorted().toList();
    }

    public static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    // Images

    /** A colour gradient; a different {@code seed} gives different colours. */
    public static BufferedImage gradient(int width, int height, int seed) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(seed);
        int base = random.nextInt(0x1000000);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = (x * 255 / Math.max(1, width - 1)) ^ (base >> 16 & 0xFF);
                int g = (y * 255 / Math.max(1, height - 1)) ^ (base >> 8 & 0xFF);
                int b = ((x + y) * 255 / Math.max(1, width + height - 2)) ^ (base & 0xFF);
                image.setRGB(x, y, r << 16 | g << 8 | b);
            }
        }
        return image;
    }

    public static byte[] png(int width, int height, int seed) {
        return write(gradient(width, height, seed), "png");
    }

    public static byte[] jpeg(int width, int height, int seed) {
        return write(gradient(width, height, seed), "jpg");
    }

    public static byte[] gif(int width, int height, int seed) {
        return write(gradient(width, height, seed), "gif");
    }

    /** One colour at one bit per pixel: 25 million pixels take 3 MB in memory and a few KB as a file. */
    public static byte[] solidPng(int width, int height) {
        return write(new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY), "png");
    }

    /** One grey at one byte per pixel. */
    public static byte[] solidJpeg(int width, int height) {
        return write(new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY), "jpg");
    }

    /** A file of {@code src/test/resources/photo}. */
    public static byte[] resource(String name) {
        try (InputStream in = PhotoSteps.class.getResourceAsStream("/photo/" + name)) {
            if (in == null) {
                throw new AssertionError("No test image /photo/" + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One colour at three bytes per pixel, as a photo is: 25 million pixels take 75 MB in memory. */
    public static byte[] solidRgbPng(int width, int height) {
        return write(new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR), "png");
    }

    /** A valid PNG of exactly {@code size} bytes: a small gradient, padded ({@link #padPng}). */
    public static byte[] pngOfSize(int size, int seed) {
        return padPng(png(320, 200, seed), size, seed);
    }

    /**
     * {@code png} made exactly {@code size} bytes long with a private chunk of noise ({@code paDd},
     * which decoders skip) after its header.
     */
    public static byte[] padPng(byte[] png, int size, int seed) {
        int padding = size - png.length - 12;
        if (padding < 0) {
            throw new IllegalArgumentException(size + " bytes is smaller than the image itself");
        }
        byte[] noise = new byte[padding];
        new Random(seed).nextBytes(noise);
        return insertChunk(png, "paDd", noise);
    }

    /** A PNG with a {@code tEXt} chunk holding {@code marker} and an {@code eXIf} chunk with the same text. */
    public static byte[] pngWithMetadata(int width, int height, String marker) {
        byte[] text = ("Comment\0" + marker).getBytes(StandardCharsets.ISO_8859_1);
        return insertChunk(insertChunk(png(width, height, 21), "eXIf", tiff(marker)), "tEXt", text);
    }

    /**
     * A JPEG with an EXIF segment (APP1) right after the start marker, as cameras write it:
     * ImageDescription {@code marker} and Orientation 1.
     */
    public static byte[] jpegWithExif(int width, int height, String marker) {
        byte[] image = jpeg(width, height, 22);
        byte[] tiff = tiff(marker);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(image, 0, 2);
        int length = 2 + 6 + tiff.length;
        out.write(0xFF);
        out.write(0xE1);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.writeBytes("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(tiff);
        out.write(image, 2, image.length - 2);
        return out.toByteArray();
    }

    /**
     * A PNG that is only its header: IHDR says {@code width} by {@code height}, and the pixel data
     * is a few bytes of nothing. Reading its size works; decoding it does not.
     */
    public static byte[] pngHeaderOnly(int width, int height) {
        ByteBuffer ihdr = ByteBuffer.allocate(13);
        ihdr.putInt(width).putInt(height).put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(PNG_SIGNATURE);
        out.writeBytes(chunk("IHDR", ihdr.array()));
        out.writeBytes(chunk("IDAT", new byte[] {0x78, (byte) 0x9C, 0x03, 0x00, 0x00, 0x00, 0x00, 0x01}));
        out.writeBytes(chunk("IEND", new byte[0]));
        return out.toByteArray();
    }

    /**
     * A WebP that is only its header: an extended header (VP8X) with a canvas of {@code width} by
     * {@code height} and an image chunk of ten zero bytes.
     */
    public static byte[] webpHeaderOnly(int width, int height) {
        ByteBuffer vp8x = ByteBuffer.allocate(8 + 10).order(ByteOrder.LITTLE_ENDIAN);
        vp8x.put("VP8X".getBytes(StandardCharsets.ISO_8859_1)).putInt(10).putInt(0);
        put24(vp8x, width - 1);
        put24(vp8x, height - 1);
        ByteBuffer vp8 = ByteBuffer.allocate(8 + 10).order(ByteOrder.LITTLE_ENDIAN);
        vp8.put("VP8 ".getBytes(StandardCharsets.ISO_8859_1)).putInt(10);
        ByteBuffer riff = ByteBuffer.allocate(12 + vp8x.capacity() + vp8.capacity()).order(ByteOrder.LITTLE_ENDIAN);
        riff.put("RIFF".getBytes(StandardCharsets.ISO_8859_1)).putInt(riff.capacity() - 8)
                .put("WEBP".getBytes(StandardCharsets.ISO_8859_1)).put(vp8x.array()).put(vp8.array());
        return riff.array();
    }

    private static void put24(ByteBuffer buffer, int value) {
        buffer.put((byte) value).put((byte) (value >> 8)).put((byte) (value >> 16));
    }

    /** A little-endian TIFF block, as EXIF holds it: ImageDescription {@code description}, Orientation 1. */
    private static byte[] tiff(String description) {
        byte[] text = (description + "\0").getBytes(StandardCharsets.ISO_8859_1);
        int dataAt = 8 + 2 + 2 * 12 + 4;
        ByteBuffer tiff = ByteBuffer.allocate(dataAt + text.length).order(ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        tiff.putShort((short) 2);
        tiff.putShort((short) 0x010E).putShort((short) 2).putInt(text.length).putInt(dataAt);
        tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putInt(1);
        tiff.putInt(0);
        tiff.put(text);
        return tiff.array();
    }

    private static byte[] insertChunk(byte[] png, String type, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(png.length + data.length + 12);
        out.write(png, 0, AFTER_IHDR);
        out.writeBytes(chunk(type, data));
        out.write(png, AFTER_IHDR, png.length - AFTER_IHDR);
        return out.toByteArray();
    }

    private static byte[] chunk(String type, byte[] data) {
        byte[] name = type.getBytes(StandardCharsets.ISO_8859_1);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        return ByteBuffer.allocate(12 + data.length).putInt(data.length).put(name).put(data)
                .putInt((int) crc.getValue()).array();
    }

    private static byte[] write(BufferedImage image, String format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("No ImageIO writer for " + format);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
