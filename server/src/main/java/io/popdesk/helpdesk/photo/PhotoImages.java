package io.popdesk.helpdesk.photo;

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi;
import io.popdesk.helpdesk.photo.PhotoHeader.Format;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Turns an uploaded photo into what is stored (design 2, ADR D-52, D-53): the first frame, decoded with
 * subsampling, scaled to fit {@value #MAX_SIDE} px with the ratio kept, turned by its EXIF orientation,
 * and written again as a PNG that holds pixels only. Nothing of the original file but its pixels gets
 * into the result (S1A-AC-21).
 *
 * <p>This decodes files strangers send. The caller checks the size of the file and reads the
 * {@link PhotoHeader} first; {@link #toPng} refuses a header over the pixel limit and asks the decoder
 * for the size again before any pixel is read (S1A-AC-23).
 */
final class PhotoImages {

    /** The stored photo fits a square of this many pixels. */
    static final int MAX_SIDE = 256;

    /** The most pixels of a PNG or JPEG: subsampling keeps their decoding small whatever the size. */
    static final long MAX_PIXELS = 25_000_000;

    /**
     * The most pixels of a WebP. Lower, because a lossless WebP or one with alpha is decoded whole before
     * it is subsampled: 12 million pixels took 72 MB of heap, 25 million 128 to 160 MB (design 2).
     */
    static final long MAX_PIXELS_WEBP = 12_000_000;

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
        BufferedImage decoded = decode(file, header.format());
        int width = decoded.getWidth();
        int height = decoded.getHeight();
        // By the size in the header, not the subsampled one, so the long side comes out at exactly 256.
        double scale = Math.min(1.0, (double) MAX_SIDE / Math.max(header.width(), header.height()));
        int targetWidth = Math.clamp(Math.round(header.width() * scale), 1, Math.min(MAX_SIDE, width));
        int targetHeight = Math.clamp(Math.round(header.height() * scale), 1, Math.min(MAX_SIDE, height));
        return write(turn(scale(decoded, targetWidth, targetHeight), header.orientation()));
    }

    /**
     * The first frame, read at about twice the stored size. The WebP reader is made from its provider
     * directly, not found through {@code ImageIO}'s registry, which depends on the class loader.
     */
    private static BufferedImage decode(byte[] file, Format format) throws IOException {
        ImageReader reader = switch (format) {
            case PNG -> ImageIO.getImageReadersByFormatName("png").next();
            case JPEG -> ImageIO.getImageReadersByFormatName("jpeg").next();
            case WEBP -> new WebPImageReaderSpi().createReaderInstance(null);
        };
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(file))) {
            reader.setInput(input, false, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width <= 0 || height <= 0) {
                throw new IOException("No size");
            }
            // The decoder allocates by its own reading of the header; it must agree with the limit too.
            if ((long) width * height > maxPixels(format)) {
                throw new TooManyPixelsException();
            }
            ImageReadParam param = reader.getDefaultReadParam();
            param.setSourceSubsampling(Math.max(1, width / (2 * MAX_SIDE)), Math.max(1, height / (2 * MAX_SIDE)), 0, 0);
            BufferedImage image = reader.read(0, param);
            if (image == null) {
                throw new IOException("No image");
            }
            return image;
        } finally {
            reader.dispose();
        }
    }

    /** Scales down in steps of at most a half, which averages pixels instead of dropping them. */
    private static BufferedImage scale(BufferedImage image, int targetWidth, int targetHeight) {
        int type = image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage current = image;
        do {
            int width = Math.max(targetWidth, current.getWidth() / 2);
            int height = Math.max(targetHeight, current.getHeight() / 2);
            BufferedImage next = new BufferedImage(width, height, type);
            Graphics2D graphics = next.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(current, 0, 0, width, height, null);
            } finally {
                graphics.dispose();
            }
            current = next;
        } while (current.getWidth() != targetWidth || current.getHeight() != targetHeight);
        return current;
    }

    /** Applies the EXIF orientation, so a photo taken upright is stored upright; the tag itself is dropped. */
    static BufferedImage turn(BufferedImage image, int orientation) {
        if (orientation < 2 || orientation > 8) {
            return image;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        boolean swapped = orientation >= 5;
        BufferedImage turned = new BufferedImage(swapped ? height : width, swapped ? width : height, image.getType());
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                switch (orientation) {
                    case 2 -> turned.setRGB(width - 1 - x, y, rgb);
                    case 3 -> turned.setRGB(width - 1 - x, height - 1 - y, rgb);
                    case 4 -> turned.setRGB(x, height - 1 - y, rgb);
                    case 5 -> turned.setRGB(y, x, rgb);
                    case 6 -> turned.setRGB(height - 1 - y, x, rgb);
                    case 7 -> turned.setRGB(height - 1 - y, width - 1 - x, rgb);
                    default -> turned.setRGB(y, width - 1 - x, rgb);
                }
            }
        }
        return turned;
    }

    /** A PNG of the pixels alone: the image carries no metadata, so the writer adds no text or EXIF chunk. */
    private static byte[] write(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), null);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
