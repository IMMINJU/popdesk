package io.popdesk.helpdesk.image;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * What an uploaded image, a profile photo or a branding logo, says about itself before any pixel is
 * decoded (S1A-AC-22, S1A-AC-23, S9-AC-16): its format, judged by its first bytes and never by its name
 * or {@code Content-Type}, its size in pixels, and the EXIF orientation. Read from the bytes directly, so
 * nothing here allocates by what the file claims.
 *
 * @param width the image's width as its header says
 * @param height the image's height as its header says
 * @param pixels the largest width times height any header in the file names; a WebP file names a
 *     size in its canvas header and again in each frame, and the decoder may allocate by any of them
 * @param orientation the EXIF orientation (tag 274), 1 to 8; 1 when the file has none
 */
public record ImageHeader(Format format, int width, int height, long pixels, int orientation) {

    public enum Format { PNG, JPEG, WEBP }

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] EXIF_PREFIX = {'E', 'x', 'i', 'f', 0, 0};
    private static final int ORIENTATION_TAG = 0x0112;

    /** The header of a PNG, JPEG, or WebP file; empty for anything else and for a file without a size. */
    public static Optional<ImageHeader> read(byte[] file) {
        if (startsWith(file, 0, PNG_SIGNATURE)) {
            return png(file);
        }
        if (file.length >= 3 && u8(file, 0) == 0xFF && u8(file, 1) == 0xD8 && u8(file, 2) == 0xFF) {
            return jpeg(file);
        }
        if (file.length >= 12 && fourcc(file, 0, "RIFF") && fourcc(file, 8, "WEBP")) {
            return webp(file);
        }
        return Optional.empty();
    }

    /** The IHDR chunk comes first: its length, its name, then width and height. */
    private static Optional<ImageHeader> png(byte[] file) {
        if (file.length < 24 || !fourcc(file, 12, "IHDR")) {
            return Optional.empty();
        }
        return sized(Format.PNG, be32(file, 16), be32(file, 20), 0, 1);
    }

    /** Walks the segments up to the first frame header (SOF), reading the EXIF segment (APP1) on the way. */
    private static Optional<ImageHeader> jpeg(byte[] file) {
        int orientation = 1;
        int at = 2;
        while (at + 4 <= file.length) {
            if (u8(file, at) != 0xFF) {
                return Optional.empty();
            }
            int marker = u8(file, at + 1);
            if (marker == 0xFF) {
                at++;
                continue;
            }
            if (marker == 0xD8 || marker == 0x01 || marker >= 0xD0 && marker <= 0xD7) {
                at += 2;
                continue;
            }
            if (marker == 0xD9 || marker == 0xDA) {
                return Optional.empty();
            }
            int length = be16(file, at + 2);
            int data = at + 4;
            int end = at + 2 + length;
            if (length < 2 || end > file.length) {
                return Optional.empty();
            }
            boolean frame = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (frame) {
                if (length < 7) {
                    return Optional.empty();
                }
                return sized(Format.JPEG, be16(file, data + 3), be16(file, data + 1), 0, orientation);
            }
            if (marker == 0xE1 && orientation == 1 && startsWith(file, data, EXIF_PREFIX)) {
                orientation = orientation(file, data + EXIF_PREFIX.length, end);
            }
            at = end;
        }
        return Optional.empty();
    }

    private static Optional<ImageHeader> webp(byte[] file) {
        WebpSizes sizes = new WebpSizes();
        sizes.walk(file, 12, file.length);
        if (sizes.width == 0) {
            return Optional.empty();
        }
        return sized(Format.WEBP, sizes.width, sizes.height, sizes.pixels, sizes.orientation);
    }

    private static Optional<ImageHeader> sized(Format format, int width, int height, long morePixels, int orientation) {
        if (width <= 0 || height <= 0) {
            return Optional.empty();
        }
        return Optional.of(new ImageHeader(format, width, height, Math.max(morePixels, (long) width * height),
                orientation));
    }

    /** Every size a WebP file names: the canvas (VP8X), each bitstream (VP8, VP8L), each animation frame. */
    private static final class WebpSizes {

        int width;
        int height;
        long pixels;
        int orientation = 1;

        void walk(byte[] file, int from, int to) {
            int at = from;
            while (at + 8 <= to) {
                long size = le32(file, at + 4) & 0xFFFFFFFFL;
                int data = at + 8;
                int end = (int) Math.min(to, data + size);
                int length = end - data;
                if (fourcc(file, at, "VP8X") && length >= 10) {
                    // The canvas is the image's size, whatever a frame said before it.
                    width = le24(file, data + 4) + 1;
                    height = le24(file, data + 7) + 1;
                    count(width, height);
                } else if (fourcc(file, at, "VP8 ") && length >= 10 && u8(file, data + 3) == 0x9D
                        && u8(file, data + 4) == 0x01 && u8(file, data + 5) == 0x2A) {
                    found(le16(file, data + 6) & 0x3FFF, le16(file, data + 8) & 0x3FFF);
                } else if (fourcc(file, at, "VP8L") && length >= 5 && u8(file, data) == 0x2F) {
                    int bits = le32(file, data + 1);
                    found((bits & 0x3FFF) + 1, (bits >>> 14 & 0x3FFF) + 1);
                } else if (fourcc(file, at, "ANMF") && length >= 16) {
                    count(le24(file, data + 6) + 1, le24(file, data + 9) + 1);
                    walk(file, data + 16, end);
                } else if (fourcc(file, at, "EXIF") && orientation == 1) {
                    int tiff = startsWith(file, data, EXIF_PREFIX) ? data + EXIF_PREFIX.length : data;
                    orientation = ImageHeader.orientation(file, tiff, end);
                }
                // Chunks are padded to an even length.
                long next = data + size + (size & 1);
                if (next > to) {
                    return;
                }
                at = (int) next;
            }
        }

        private void found(int w, int h) {
            if (width == 0) {
                width = w;
                height = h;
            }
            count(w, h);
        }

        private void count(int w, int h) {
            pixels = Math.max(pixels, (long) w * h);
        }
    }

    /**
     * The orientation in a TIFF block ({@code file[from, to)}): tag 274 of the first directory. 1 when
     * the block is not TIFF, has no such tag, or names a value outside 1 to 8.
     */
    private static int orientation(byte[] file, int from, int to) {
        if (from < 0 || to > file.length || to - from < 14) {
            return 1;
        }
        ByteBuffer tiff = ByteBuffer.wrap(file, from, to - from).slice();
        int order = tiff.getShort(0);
        if (order == 0x4949) {
            tiff.order(ByteOrder.LITTLE_ENDIAN);
        } else if (order != 0x4D4D) {
            return 1;
        }
        if (tiff.getShort(2) != 42) {
            return 1;
        }
        long directory = tiff.getInt(4) & 0xFFFFFFFFL;
        if (directory + 2 > tiff.limit()) {
            return 1;
        }
        int entries = tiff.getShort((int) directory) & 0xFFFF;
        for (int i = 0; i < entries; i++) {
            long entry = directory + 2 + 12L * i;
            if (entry + 12 > tiff.limit()) {
                return 1;
            }
            if ((tiff.getShort((int) entry) & 0xFFFF) == ORIENTATION_TAG) {
                int value = tiff.getShort((int) entry + 8) & 0xFFFF;
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    private static boolean startsWith(byte[] file, int at, byte[] prefix) {
        if (at < 0 || at + prefix.length > file.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (file[at + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean fourcc(byte[] file, int at, String name) {
        return startsWith(file, at, name.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static int u8(byte[] file, int at) {
        return file[at] & 0xFF;
    }

    private static int be16(byte[] file, int at) {
        return u8(file, at) << 8 | u8(file, at + 1);
    }

    private static int be32(byte[] file, int at) {
        return be16(file, at) << 16 | be16(file, at + 2);
    }

    private static int le16(byte[] file, int at) {
        return u8(file, at) | u8(file, at + 1) << 8;
    }

    private static int le24(byte[] file, int at) {
        return le16(file, at) | u8(file, at + 2) << 16;
    }

    private static int le32(byte[] file, int at) {
        return le16(file, at) | le16(file, at + 2) << 16;
    }
}
