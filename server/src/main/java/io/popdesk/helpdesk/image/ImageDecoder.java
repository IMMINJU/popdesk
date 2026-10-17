package io.popdesk.helpdesk.image;

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi;
import io.popdesk.helpdesk.image.ImageHeader.Format;
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
 * Turns an uploaded image into what is stored (ADR D-52, D-53; slice 9 design 0절 2): the first frame,
 * decoded with subsampling, turned by its EXIF orientation, scaled to fit a box with the ratio kept and
 * never made larger, and written again as a PNG that holds pixels only. The box is given as the image is
 * shown, so it is fitted after the turn: a phone photo stored on its side fits by its upright size. Nothing
 * of the original file but its pixels gets into the result (S1A-AC-21, S9-AC-15).
 *
 * <p>This decodes files strangers send. The caller checks the size of the file, reads the
 * {@link ImageHeader} first, and holds a place of {@link ImageDecoding}; {@link #toPng} refuses a header
 * over the pixel limit and asks the decoder for the size again before any pixel is read (S1A-AC-23).
 */
public final class ImageDecoder {

    /** The most pixels of a PNG or JPEG: subsampling keeps their decoding small whatever the size. */
    public static final long MAX_PIXELS = 25_000_000;

    /**
     * The most pixels of a WebP. Lower, because a lossless WebP or one with alpha is decoded whole before
     * it is subsampled: 12 million pixels took 72 MB of heap, 25 million 128 to 160 MB (slice 1a design 2).
     */
    public static final long MAX_PIXELS_WEBP = 12_000_000;

    /** The header names more pixels than the format's limit. */
    public static final class TooManyPixelsException extends IOException {

        TooManyPixelsException() {
            super("Too many pixels");
        }
    }

    /** A PNG popdesk wrote and its size in pixels. */
    public record Png(byte[] bytes, int width, int height) {}

    private ImageDecoder() {}

    public static long maxPixels(Format format) {
        return format == Format.WEBP ? MAX_PIXELS_WEBP : MAX_PIXELS;
    }

    /**
     * The image to store, fitted into {@code boxWidth} by {@code boxHeight} after it is turned. The same file
     * always gives the same PNG. Throws {@link TooManyPixelsException} before decoding when the file is over
     * the pixel limit, and {@link IOException} or a runtime exception when it cannot be decoded.
     */
    public static Png toPng(byte[] file, ImageHeader header, int boxWidth, int boxHeight) throws IOException {
        if (header.pixels() > maxPixels(header.format())) {
            throw new TooManyPixelsException();
        }
        boolean swapped = header.orientation() >= 5 && header.orientation() <= 8;
        int shownWidth = swapped ? header.height() : header.width();
        int shownHeight = swapped ? header.width() : header.height();
        // By the size in the header, not the subsampled one, so a side that meets the box comes out exactly at it.
        double scale = Math.min(1.0, Math.min((double) boxWidth / shownWidth, (double) boxHeight / shownHeight));
        int fittedWidth = Math.clamp(Math.round(shownWidth * scale), 1, boxWidth);
        int fittedHeight = Math.clamp(Math.round(shownHeight * scale), 1, boxHeight);
        // The same size in the file's own axes, before the turn.
        int targetWidth = swapped ? fittedHeight : fittedWidth;
        int targetHeight = swapped ? fittedWidth : fittedHeight;
        BufferedImage decoded = decode(file, header.format(), targetWidth, targetHeight);
        BufferedImage scaled = scale(decoded, Math.min(targetWidth, decoded.getWidth()),
                Math.min(targetHeight, decoded.getHeight()));
        BufferedImage turned = turn(scaled, header.orientation());
        return new Png(write(turned), turned.getWidth(), turned.getHeight());
    }

    /**
     * The first frame, read at about twice the target size, with one subsampling step for both axes so the
     * ratio stays. The WebP reader is made from its provider directly, not found through {@code ImageIO}'s
     * registry, which depends on the class loader.
     */
    private static BufferedImage decode(byte[] file, Format format, int targetWidth, int targetHeight)
            throws IOException {
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
            int step = Math.max(1, Math.min(width / (2 * targetWidth), height / (2 * targetHeight)));
            param.setSourceSubsampling(step, step, 0, 0);
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

    /** Applies the EXIF orientation, so an image taken upright is stored upright; the tag itself is dropped. */
    public static BufferedImage turn(BufferedImage image, int orientation) {
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

    /**
     * A PNG of the pixels alone: the image carries no metadata, so the writer adds no text, EXIF, or color chunk;
     * the chunks are IHDR, IDAT, and IEND.
     */
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
