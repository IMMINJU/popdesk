package io.popdesk.helpdesk.photo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.popdesk.helpdesk.photo.PhotoHeader.Format;
import io.popdesk.helpdesk.photo.PhotoImages.TooManyPixelsException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * What the HTTP tests of the photo upload do not reach: the EXIF orientation is read and applied, and a
 * WebP whose frame names a larger size than its canvas is refused by the larger one.
 */
class PhotoImagesTest {

    private static final int RED = 0xFFFF0000;
    private static final int BLUE = 0xFF0000FF;
    private static final int GREEN = 0xFF00FF00;

    /** A JPEG, red on its left half and blue on its right half, with an EXIF segment that names {@code orientation}. */
    private static byte[] jpegLeftRedRightBlue(int width, int height, int orientation) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, width / 2, height);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(width / 2, 0, width - width / 2, height);
        graphics.dispose();
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", plain);
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

    private static boolean red(int rgb) {
        return (rgb >> 16 & 0xFF) > 180 && (rgb & 0xFF) < 80;
    }

    private static boolean blue(int rgb) {
        return (rgb & 0xFF) > 180 && (rgb >> 16 & 0xFF) < 80;
    }

    @Test
    @DisplayName("a JPEG whose EXIF says 'turn 90 degrees clockwise to show' is stored turned: its left side is the top")
    void exifOrientationIsApplied() throws IOException {
        byte[] file = jpegLeftRedRightBlue(400, 200, 6);
        PhotoHeader header = PhotoHeader.read(file).orElseThrow();
        assertThat(header.format()).isEqualTo(Format.JPEG);
        assertThat(header.width()).isEqualTo(400);
        assertThat(header.height()).isEqualTo(200);
        assertThat(header.orientation()).isEqualTo(6);

        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(PhotoImages.toPng(file, header)));

        assertThat(stored.getWidth()).as("width of the stored photo").isEqualTo(128);
        assertThat(stored.getHeight()).as("height of the stored photo").isEqualTo(256);
        assertThat(red(stored.getRGB(64, 20))).as("the top of the stored photo is the original's left, red").isTrue();
        assertThat(blue(stored.getRGB(64, 235))).as("the bottom of the stored photo is the original's right, blue")
                .isTrue();
    }

    @Test
    @DisplayName("a JPEG without EXIF is stored as it is")
    void withoutExifNothingIsTurned() throws IOException {
        byte[] withExif = jpegLeftRedRightBlue(400, 200, 1);
        // Cut the APP1 segment out again: the start marker, then everything after the segment.
        int segment = 2 + 2 + ((withExif[4] & 0xFF) << 8 | withExif[5] & 0xFF);
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        plain.write(withExif, 0, 2);
        plain.write(withExif, segment, withExif.length - segment);
        byte[] file = plain.toByteArray();
        PhotoHeader header = PhotoHeader.read(file).orElseThrow();
        assertThat(header.orientation()).isEqualTo(1);

        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(PhotoImages.toPng(file, header)));

        assertThat(stored.getWidth()).isEqualTo(256);
        assertThat(stored.getHeight()).isEqualTo(128);
        assertThat(red(stored.getRGB(20, 64))).as("left is red").isTrue();
        assertThat(blue(stored.getRGB(235, 64))).as("right is blue").isTrue();
    }

    @ParameterizedTest(name = "orientation {0}")
    @CsvSource({
            // orientation, size of the result, where the original's top-left and top-right corners go
            "1, 3, 2, 0, 0, 2, 0",
            "2, 3, 2, 2, 0, 0, 0",
            "3, 3, 2, 2, 1, 0, 1",
            "4, 3, 2, 0, 1, 2, 1",
            "5, 2, 3, 0, 0, 0, 2",
            "6, 2, 3, 1, 0, 1, 2",
            "7, 2, 3, 1, 2, 1, 0",
            "8, 2, 3, 0, 2, 0, 0"})
    @DisplayName("each of the eight EXIF orientations moves the corners of a 3x2 image to where the tag says")
    void everyOrientation(int orientation, int width, int height, int leftX, int leftY, int rightX, int rightY) {
        BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 3; x++) {
                image.setRGB(x, y, GREEN);
            }
        }
        image.setRGB(0, 0, RED);
        image.setRGB(2, 0, BLUE);

        BufferedImage turned = PhotoImages.turn(image, orientation);

        assertThat(turned.getWidth()).as("width").isEqualTo(width);
        assertThat(turned.getHeight()).as("height").isEqualTo(height);
        assertThat(turned.getRGB(leftX, leftY)).as("the original's top-left corner").isEqualTo(RED);
        assertThat(turned.getRGB(rightX, rightY)).as("the original's top-right corner").isEqualTo(BLUE);
    }

    @Test
    @DisplayName("a WebP with a small canvas and a frame that names 16384x16384 is over the pixel limit")
    void webpIsJudgedByItsLargestSize() {
        ByteBuffer vp8x = ByteBuffer.allocate(8 + 10).order(ByteOrder.LITTLE_ENDIAN);
        vp8x.put("VP8X".getBytes(StandardCharsets.ISO_8859_1)).putInt(10).putInt(0);
        vp8x.put((byte) 99).put((byte) 0).put((byte) 0).put((byte) 99).put((byte) 0).put((byte) 0);
        ByteBuffer vp8l = ByteBuffer.allocate(8 + 6).order(ByteOrder.LITTLE_ENDIAN);
        // Signature 0x2F, then 14 bits of width - 1 and 14 bits of height - 1, both 16383.
        vp8l.put("VP8L".getBytes(StandardCharsets.ISO_8859_1)).putInt(6).put((byte) 0x2F).putInt(0x0FFFFFFF)
                .put((byte) 0);
        ByteBuffer riff = ByteBuffer.allocate(12 + vp8x.capacity() + vp8l.capacity()).order(ByteOrder.LITTLE_ENDIAN);
        riff.put("RIFF".getBytes(StandardCharsets.ISO_8859_1)).putInt(riff.capacity() - 8)
                .put("WEBP".getBytes(StandardCharsets.ISO_8859_1)).put(vp8x.array()).put(vp8l.array());
        byte[] file = riff.array();

        PhotoHeader header = PhotoHeader.read(file).orElseThrow();

        assertThat(header.format()).isEqualTo(Format.WEBP);
        assertThat(header.width()).as("width of the canvas").isEqualTo(100);
        assertThat(header.height()).as("height of the canvas").isEqualTo(100);
        assertThat(header.pixels()).as("the largest size the file names").isEqualTo(16384L * 16384L);
        assertThatThrownBy(() -> PhotoImages.toPng(file, header)).isInstanceOf(TooManyPixelsException.class);
    }

    @Test
    @DisplayName("bytes that only begin like an image have no header")
    void brokenFilesHaveNoHeader() {
        assertThat(PhotoHeader.read(new byte[0])).isEmpty();
        assertThat(PhotoHeader.read(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})).isEmpty();
        assertThat(PhotoHeader.read("RIFF\0\0\0\0WEBP".getBytes(StandardCharsets.ISO_8859_1))).isEmpty();
        assertThat(PhotoHeader.read("GIF89a and nothing more".getBytes(StandardCharsets.ISO_8859_1))).isEmpty();
    }
}
