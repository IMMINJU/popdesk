package io.popdesk.helpdesk.photo;

import io.popdesk.helpdesk.image.ImageHeader;
import java.util.Optional;

/**
 * What an uploaded photo says about itself before any pixel is decoded (S1A-AC-22, S1A-AC-23), as
 * {@link ImageHeader} reads it for photos and logos alike: its format, judged by its first bytes and
 * never by its name or {@code Content-Type}, its size in pixels, and the EXIF orientation.
 *
 * @param width the image's width as its header says
 * @param height the image's height as its header says
 * @param pixels the largest width times height any header in the file names; a WebP file names a
 *     size in its canvas header and again in each frame, and the decoder may allocate by any of them
 * @param orientation the EXIF orientation (tag 274), 1 to 8; 1 when the file has none
 */
record PhotoHeader(Format format, int width, int height, long pixels, int orientation) {

    enum Format { PNG, JPEG, WEBP }

    /** The header of a PNG, JPEG, or WebP file; empty for anything else and for a file without a size. */
    static Optional<PhotoHeader> read(byte[] file) {
        return ImageHeader.read(file).map(h -> new PhotoHeader(Format.valueOf(h.format().name()), h.width(),
                h.height(), h.pixels(), h.orientation()));
    }

    /** The same header as {@link ImageHeader} has it. */
    ImageHeader image() {
        return new ImageHeader(ImageHeader.Format.valueOf(format.name()), width, height, pixels, orientation);
    }
}
