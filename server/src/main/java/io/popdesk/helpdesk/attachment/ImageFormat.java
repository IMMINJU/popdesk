package io.popdesk.helpdesk.attachment;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The image formats a content takes (ATT-6): PNG, JPEG, GIF, and WebP. A file's format is told by its
 * first bytes alone, never by its name or the type the upload claims, and the format decides the
 * {@code Content-Type} the image is served with (S5-AC-02, S5-AC-03, TM-19). Nothing here decodes an
 * image: bytes that start like one of the formats are that format, whatever follows. SVG is text and
 * can carry scripts; it is none of the four.
 */
public enum ImageFormat {
    PNG("image/png"),
    JPEG("image/jpeg"),
    GIF("image/gif"),
    WEBP("image/webp");

    /** The most bytes from the start of a file that {@link #of} looks at: the length of the longest signature. */
    public static final int HEAD_LENGTH = 12;

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] GIF_87A = "GIF87a".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GIF_89A = "GIF89a".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RIFF = "RIFF".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] WEBP_FORM = "WEBP".getBytes(StandardCharsets.US_ASCII);

    /** Where the form type of a RIFF file starts: after {@code RIFF} and the four bytes of its length. */
    private static final int RIFF_FORM_AT = 8;

    private final String contentType;

    ImageFormat(String contentType) {
        this.contentType = contentType;
    }

    /** The {@code Content-Type} an image of this format is stored and served with, for example {@code image/png}. */
    public String contentType() {
        return contentType;
    }

    /**
     * The format of the file that starts with these bytes.
     *
     * @param head the first bytes of the file, in order from its first byte. Any length: only the first
     *     {@value #HEAD_LENGTH} are looked at, so the whole file may be given, and the array is not
     *     changed. {@code null}, an empty array, and an array shorter than the signature of a format are
     *     not that format; they are no error.
     * @return the format whose signature the bytes start with, or empty when they start with none:
     *     <ul>
     *       <li>{@link #PNG}: the 8 bytes {@code 89 50 4E 47 0D 0A 1A 0A};
     *       <li>{@link #JPEG}: the 3 bytes {@code FF D8 FF};
     *       <li>{@link #GIF}: the 6 ASCII characters {@code GIF87a} or {@code GIF89a}, in this letter
     *           case, and no other version;
     *       <li>{@link #WEBP}: the 4 ASCII characters {@code RIFF}, then any 4 bytes (the length), then
     *           the 4 ASCII characters {@code WEBP}, 12 bytes in all. A RIFF file of another kind is none.
     *     </ul>
     *     Never {@code null}.
     */
    public static Optional<ImageFormat> of(byte[] head) {
        if (head == null) {
            return Optional.empty();
        }
        if (startsWith(head, 0, PNG_SIGNATURE)) {
            return Optional.of(PNG);
        }
        if (startsWith(head, 0, JPEG_SIGNATURE)) {
            return Optional.of(JPEG);
        }
        if (startsWith(head, 0, GIF_87A) || startsWith(head, 0, GIF_89A)) {
            return Optional.of(GIF);
        }
        if (startsWith(head, 0, RIFF) && startsWith(head, RIFF_FORM_AT, WEBP_FORM)) {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }

    /** Whether {@code bytes} holds all of {@code signature} from index {@code at} on. */
    private static boolean startsWith(byte[] bytes, int at, byte[] signature) {
        if (bytes.length < at + signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (bytes[at + i] != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
