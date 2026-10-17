package io.popdesk.helpdesk.photo;

import io.popdesk.helpdesk.image.ImageDecoder;
import io.popdesk.helpdesk.photo.PhotoHeader.Format;
import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * Turns an uploaded photo into what is stored (design 2, ADR D-52, D-53): {@link ImageDecoder} with a
 * square box of {@value #MAX_SIDE} px, so the first frame, decoded with subsampling, turned by its EXIF
 * orientation, scaled to fit with the ratio kept, and written again as a PNG that holds pixels only.
 * Nothing of the original file but its pixels gets into the result (S1A-AC-21).
 *
 * <p>This decodes files strangers send. The caller checks the size of the file and reads the
 * {@link PhotoHeader} first; {@link #toPng} refuses a header over the pixel limit and asks the decoder
 * for the size again before any pixel is read (S1A-AC-23).
 */
final class PhotoImages {

    /** The stored photo fits a square of this many pixels. */
    static final int MAX_SIDE = 256;

    /** The most pixels of a PNG or JPEG: subsampling keeps their decoding small whatever the size. */
    static final long MAX_PIXELS = ImageDecoder.MAX_PIXELS;

    /** The most pixels of a WebP, lower because a lossless one is decoded whole ({@link ImageDecoder}). */
    static final long MAX_PIXELS_WEBP = ImageDecoder.MAX_PIXELS_WEBP;

    /** The header names more pixels than the format's limit. */
    static final class TooManyPixelsException extends IOException {

        TooManyPixelsException() {
            super("Too many pixels");
        }
    }

    private PhotoImages() {}

    static long maxPixels(Format format) {
        return format == Format.WEBP ? MAX_PIXELS_WEBP : MAX_PIXELS;
    }

    /**
     * The photo to store. Throws {@link TooManyPixelsException} before decoding when the file is over
     * the pixel limit, and {@link IOException} or a runtime exception when it cannot be decoded.
     */
    static byte[] toPng(byte[] file, PhotoHeader header) throws IOException {
        if (header.pixels() > maxPixels(header.format())) {
            throw new TooManyPixelsException();
        }
        try {
            return ImageDecoder.toPng(file, header.image(), MAX_SIDE, MAX_SIDE).bytes();
        } catch (ImageDecoder.TooManyPixelsException e) {
            throw new TooManyPixelsException();
        }
    }

    /** Applies the EXIF orientation, so a photo taken upright is stored upright; the tag itself is dropped. */
    static BufferedImage turn(BufferedImage image, int orientation) {
        return ImageDecoder.turn(image, orientation);
    }
}
