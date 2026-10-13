package io.popdesk.helpdesk.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Which first bytes of a file are read as PNG, JPEG, GIF, or WebP (S5-AC-02), and the
 * {@code Content-Type} each format is served with (S5-AC-03). Written from the Javadoc of
 * {@link ImageFormat} alone. Every provider below gives a label first and the bytes last.
 */
class ImageFormatTest {

    private static final byte[] PNG = hex("89 50 4E 47 0D 0A 1A 0A");
    private static final byte[] JPEG = hex("FF D8 FF");
    private static final byte[] GIF_87A = ascii("GIF87a");
    private static final byte[] GIF_89A = ascii("GIF89a");
    private static final byte[] WEBP = concat(ascii("RIFF"), hex("24 00 00 00"), ascii("WEBP"));

    private record Signature(String label, ImageFormat format, byte[] bytes) {

        /** The positions the signature fixes: all of them, except the four length bytes of a RIFF file. */
        IntStream fixedPositions() {
            return IntStream.range(0, bytes.length).filter(at -> format != ImageFormat.WEBP || at < 4 || at >= 8);
        }
    }

    private static final List<Signature> SIGNATURES = List.of(
            new Signature("PNG", ImageFormat.PNG, PNG),
            new Signature("JPEG", ImageFormat.JPEG, JPEG),
            new Signature("GIF87a", ImageFormat.GIF, GIF_87A),
            new Signature("GIF89a", ImageFormat.GIF, GIF_89A),
            new Signature("WebP", ImageFormat.WEBP, WEBP));

    // ---- the four formats ----

    static Stream<Arguments> exactSignatures() {
        return SIGNATURES.stream().map(s -> Arguments.of(s.label() + " signature alone", s.format(), s.bytes()));
    }

    @ParameterizedTest(name = "S5-AC-02 {0} is {1}")
    @MethodSource("exactSignatures")
    @DisplayName("S5-AC-02 exactly the bytes of a signature are that format")
    void signatureAloneIsTheFormat(String label, ImageFormat format, byte[] head) {
        assertThat(ImageFormat.of(head)).contains(format);
    }

    static Stream<Arguments> signaturesWithMoreBytes() {
        List<Arguments> cases = new ArrayList<>();
        for (Signature s : SIGNATURES) {
            for (int length = s.bytes().length + 1; length <= ImageFormat.HEAD_LENGTH + 4; length++) {
                for (int filler : new int[] {0x00, 0xFF, 'G'}) {
                    byte[] head = Arrays.copyOf(s.bytes(), length);
                    Arrays.fill(head, s.bytes().length, length, (byte) filler);
                    cases.add(Arguments.of(
                            "%s then %d bytes of %02X".formatted(s.label(), length - s.bytes().length, filler),
                            s.format(),
                            head));
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "S5-AC-02 {0} is {1}")
    @MethodSource("signaturesWithMoreBytes")
    @DisplayName("S5-AC-02 a signature is that format whatever follows it")
    void signatureWithMoreBytesIsTheFormat(String label, ImageFormat format, byte[] head) {
        assertThat(ImageFormat.of(head)).contains(format);
    }

    static Stream<Arguments> wholeFiles() {
        byte[] counting = new byte[4096];
        for (int i = 0; i < counting.length; i++) {
            counting[i] = (byte) (i * 31 + 7);
        }
        byte[] otherSignatures = concat(PNG, JPEG, GIF_87A, GIF_89A, WEBP, ascii("<svg onload=alert(1)>"));
        List<Arguments> cases = new ArrayList<>();
        for (Signature s : SIGNATURES) {
            cases.add(Arguments.of(s.label() + " file of zeros", s.format(), concat(s.bytes(), new byte[4096])));
            cases.add(Arguments.of(s.label() + " file of mixed bytes", s.format(), concat(s.bytes(), counting)));
            cases.add(Arguments.of(
                    s.label() + " file that goes on with the other signatures",
                    s.format(),
                    concat(s.bytes(), otherSignatures)));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "S5-AC-02 {0} is {1}")
    @MethodSource("wholeFiles")
    @DisplayName("S5-AC-02 a whole file longer than HEAD_LENGTH is the format it starts with")
    void wholeFileIsTheFormatItStartsWith(String label, ImageFormat format, byte[] file) {
        assertThat(file.length).isGreaterThan(ImageFormat.HEAD_LENGTH);

        assertThat(ImageFormat.of(file)).contains(format);
    }

    @ParameterizedTest(name = "S5-AC-02 the first HEAD_LENGTH bytes of {0} give {1} too")
    @MethodSource("wholeFiles")
    @DisplayName("S5-AC-02 the first HEAD_LENGTH bytes of a file say what the whole file says")
    void headOfTheFileIsEnough(String label, ImageFormat format, byte[] file) {
        assertThat(ImageFormat.of(Arrays.copyOf(file, ImageFormat.HEAD_LENGTH))).contains(format);
    }

    static Stream<Arguments> jpegMarkers() {
        return Stream.of("FF D8 FF E0", "FF D8 FF E1", "FF D8 FF DB", "FF D8 FF EE", "FF D8 FF 00", "FF D8 FF FF")
                .map(h -> Arguments.of(h, hex(h)));
    }

    @ParameterizedTest(name = "S5-AC-02 {0} is JPEG")
    @MethodSource("jpegMarkers")
    @DisplayName("S5-AC-02 a JPEG is told by its first 3 bytes, whatever the fourth is")
    void jpegIsToldByThreeBytes(String label, byte[] head) {
        assertThat(ImageFormat.of(head)).contains(ImageFormat.JPEG);
    }

    static Stream<Arguments> webpLengths() {
        return Stream.of(
                        Arguments.of("00 00 00 00", hex("00 00 00 00")),
                        Arguments.of("FF FF FF FF", hex("FF FF FF FF")),
                        Arguments.of("80 7F 80 7F", hex("80 7F 80 7F")),
                        Arguments.of("the letters WEBP", ascii("WEBP")),
                        Arguments.of("the letters RIFF", ascii("RIFF")),
                        Arguments.of("the letters WAVE", ascii("WAVE")))
                .map(a -> Arguments.of(a.get()[0], concat(ascii("RIFF"), (byte[]) a.get()[1], ascii("WEBP"))));
    }

    @ParameterizedTest(name = "S5-AC-02 a WebP whose length bytes are {0} is WebP")
    @MethodSource("webpLengths")
    @DisplayName("S5-AC-02 the four length bytes of a WebP may be any bytes")
    void webpLengthMayBeAnyBytes(String label, byte[] head) {
        assertThat(ImageFormat.of(head)).contains(ImageFormat.WEBP);
    }

    @Test
    @DisplayName("S5-AC-02 bytes with the high bit set are compared as bytes")
    void highBitBytesAreComparedAsBytes() {
        assertThat(ImageFormat.of(new byte[] {-119, 80, 78, 71, 13, 10, 26, 10})).contains(ImageFormat.PNG);
        assertThat(ImageFormat.of(new byte[] {-1, -40, -1})).contains(ImageFormat.JPEG);
        assertThat(ImageFormat.of(new byte[] {0x09, 80, 78, 71, 13, 10, 26, 10})).isEmpty();
        assertThat(ImageFormat.of(new byte[] {0x7F, 0x58, 0x7F})).isEmpty();
        assertThat(ImageFormat.of(new byte[] {0x7F, -40, -1})).isEmpty();
        assertThat(ImageFormat.of(new byte[] {-1, 0x58, -1})).isEmpty();
        assertThat(ImageFormat.of(new byte[] {-1, -40, 0x7F})).isEmpty();
    }

    // ---- what is none of the four ----

    @Test
    @DisplayName("S5-AC-02 no bytes at all are no format and no error")
    void nullAndEmptyAreNoFormat() {
        assertThat(ImageFormat.of(null)).isNotNull().isEmpty();
        assertThat(ImageFormat.of(new byte[0])).isNotNull().isEmpty();
    }

    static Stream<Arguments> tooShort() {
        List<Arguments> cases = new ArrayList<>();
        for (Signature s : SIGNATURES) {
            for (int length = 1; length < s.bytes().length; length++) {
                cases.add(Arguments.of(
                        "the first %d of the %d bytes of %s".formatted(length, s.bytes().length, s.label()),
                        Arrays.copyOf(s.bytes(), length)));
            }
        }
        for (int single : new int[] {0x00, 0x89, 0xFF, 'G', 'R', '<'}) {
            cases.add(Arguments.of("the one byte %02X".formatted(single), new byte[] {(byte) single}));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "S5-AC-02 {0} is no format")
    @MethodSource("tooShort")
    @DisplayName("S5-AC-02 bytes shorter than a signature are not that format")
    void shorterThanTheSignatureIsNoFormat(String label, byte[] head) {
        assertThat(ImageFormat.of(head)).isEmpty();
    }

    static Stream<Arguments> oneByteChanged() {
        List<Arguments> cases = new ArrayList<>();
        for (Signature s : SIGNATURES) {
            s.fixedPositions().forEach(at -> {
                for (int flip : new int[] {0x01, 0x20, 0x80, 0xFF}) {
                    // Padded to a whole head and beyond, so that length is not what makes it fail.
                    byte[] head = concat(s.bytes(), new byte[ImageFormat.HEAD_LENGTH]);
                    head[at] ^= (byte) flip;
                    cases.add(Arguments.of(
                            "%s with byte %d changed by %02X".formatted(s.label(), at, flip), head));
                    byte[] bare = s.bytes().clone();
                    bare[at] ^= (byte) flip;
                    cases.add(Arguments.of(
                            "%s alone with byte %d changed by %02X".formatted(s.label(), at, flip), bare));
                }
            });
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "S5-AC-02 {0} is no format")
    @MethodSource("oneByteChanged")
    @DisplayName("S5-AC-02 a signature with one byte changed is no format")
    void oneByteChangedIsNoFormat(String label, byte[] head) {
        assertThat(ImageFormat.of(head)).isEmpty();
    }

    static Stream<Arguments> wrongGifs() {
        return Stream.of(
                        "GIF88a", "GIF86a", "GIF90a", "GIF97a", "GIF79a", "GIF89b", "GIF87b", "GIF89A", "GIF87A",
                        "gif89a", "gif87a", "Gif89a", "GIF8", "GIF89", "GIF 89a", " GIF89a", "GIF", "GIFGIF89a",
                        "FIG89a", "GIF8\u00009a")
                .flatMap(text -> Stream.of(
                        Arguments.of("\"" + text + "\"", ascii(text)),
                        Arguments.of(
                                "\"" + text + "\" and a file after it",
                                concat(ascii(text), new byte[ImageFormat.HEAD_LENGTH]))));
    }

    @ParameterizedTest(name = "S5-AC-02 {0} is no format")
    @MethodSource("wrongGifs")
    @DisplayName("S5-AC-02 only GIF87a and GIF89a in this letter case are GIF")
    void otherGifSpellingsAreNoFormat(String label, byte[] head) {
        assertThat(ImageFormat.of(head)).isEmpty();
    }

    static Stream<Arguments> wrongWebps() {
        byte[] length = hex("24 00 00 00");
        byte[] more = new byte[ImageFormat.HEAD_LENGTH];
        return Stream.of(
                Arguments.of("a WAVE file", concat(ascii("RIFF"), length, ascii("WAVE"), more)),
                Arguments.of("an AVI file", concat(ascii("RIFF"), length, ascii("AVI "), more)),
                Arguments.of("an ANI file", concat(ascii("RIFF"), length, ascii("ACON"), more)),
                Arguments.of("RIFF with the form in lower case", concat(ascii("RIFF"), length, ascii("webp"), more)),
                Arguments.of("RIFF with the form WebP", concat(ascii("RIFF"), length, ascii("WebP"), more)),
                Arguments.of("riff in lower case", concat(ascii("riff"), length, ascii("WEBP"), more)),
                Arguments.of("RIFX", concat(ascii("RIFX"), length, ascii("WEBP"), more)),
                Arguments.of("WEBP right after RIFF", concat(ascii("RIFFWEBP"), length, more)),
                Arguments.of("WEBP one byte early", concat(ascii("RIFF"), hex("24 00 00"), ascii("WEBP"), more)),
                Arguments.of("WEBP one byte late", concat(ascii("RIFF"), hex("24 00 00 00 00"), ascii("WEBP"), more)),
                Arguments.of("WEBP after the head", concat(ascii("RIFF"), length, ascii("WAVE"), ascii("WEBP"))),
                Arguments.of("WEBP first and RIFF last", concat(ascii("WEBP"), length, ascii("RIFF"), more)),
                Arguments.of("WEBP with no RIFF before it", concat(ascii("WEBP"), more)),
                Arguments.of("zeros where RIFF goes", concat(new byte[8], ascii("WEBP"), more)),
                Arguments.of("RIFF and the length alone", concat(ascii("RIFF"), length)),
                Arguments.of("RIFF and zeros", concat(ascii("RIFF"), more)),
                Arguments.of("only 11 bytes of a WebP", concat(ascii("RIFF"), length, ascii("WEB"))),
                Arguments.of("11 bytes of a WebP with the P one early", concat(ascii("RIFF"), hex("24 00 00"), ascii("WEBP"))));
    }

    @ParameterizedTest(name = "S5-AC-02 {0} is no format")
    @MethodSource("wrongWebps")
    @DisplayName("S5-AC-02 a RIFF file of another kind, or WEBP at another place, is no format")
    void otherRiffFilesAreNoFormat(String label, byte[] head) {
        assertThat(ImageFormat.of(head)).isEmpty();
    }

    static Stream<Arguments> notOneOfTheFour() {
        byte[] more = new byte[ImageFormat.HEAD_LENGTH];
        return Stream.of(
                Arguments.of("BMP", concat(ascii("BM"), hex("36 00 0C 00 00 00 00 00 36 00 00 00"), more)),
                Arguments.of("TIFF, little endian", concat(hex("49 49 2A 00 08 00 00 00"), more)),
                Arguments.of("TIFF, big endian", concat(hex("4D 4D 00 2A 00 00 00 08"), more)),
                Arguments.of("ICO", concat(hex("00 00 01 00 01 00 10 10"), more)),
                Arguments.of("AVIF", concat(hex("00 00 00 20"), ascii("ftypavif"), more)),
                Arguments.of("HEIC", concat(hex("00 00 00 18"), ascii("ftypheic"), more)),
                Arguments.of("JPEG 2000 codestream", concat(hex("FF 4F FF 51"), more)),
                Arguments.of("JPEG XL", concat(hex("FF 0A"), more)),
                Arguments.of("PDF", ascii("%PDF-1.7\n%\n1 0 obj\n")),
                Arguments.of("ZIP", concat(ascii("PK"), hex("03 04 14 00"), more)),
                Arguments.of("a Windows program", concat(ascii("MZ"), hex("90 00 03 00"), more)),
                Arguments.of("SVG", ascii("<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"/>")),
                Arguments.of("SVG with an XML declaration", ascii("<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\"/>")),
                Arguments.of("HTML", ascii("<!doctype html><script>alert(1)</script>")),
                Arguments.of("HTML in upper case", ascii("<!DOCTYPE html><html></html>")),
                Arguments.of("plain text", ascii("hello, this is not an image")),
                Arguments.of("the letters PNG", ascii("PNG\r\n and more text")),
                Arguments.of("the letters JPEG and JFIF", ascii("JPEG JFIF file")),
                Arguments.of("twelve zeros", new byte[ImageFormat.HEAD_LENGTH]),
                Arguments.of("a long file of zeros", new byte[4096]),
                Arguments.of("PNG after a zero byte", concat(hex("00"), PNG, more)),
                Arguments.of("PNG after a UTF-8 byte order mark", concat(hex("EF BB BF"), PNG, more)),
                Arguments.of("PNG after a space", concat(ascii(" "), PNG, more)),
                Arguments.of("PNG after a line feed", concat(ascii("\n"), PNG, more)),
                Arguments.of("PNG without its first byte", concat(ascii("PNG\r\n"), hex("1A 0A"), more)),
                Arguments.of("JPEG after a zero byte", concat(hex("00"), JPEG, more)),
                Arguments.of("JPEG after FF", concat(hex("FF"), JPEG, more)),
                Arguments.of("GIF89a after a UTF-8 byte order mark", concat(hex("EF BB BF"), GIF_89A, more)),
                Arguments.of("GIF89a after a space", concat(ascii(" "), GIF_89A, more)),
                Arguments.of("WebP after a zero byte", concat(hex("00"), WEBP, more)),
                Arguments.of("PNG right after the head", concat(more, PNG)),
                Arguments.of("JPEG right after the head", concat(more, JPEG)),
                Arguments.of("GIF89a right after the head", concat(more, GIF_89A)),
                Arguments.of("WebP right after the head", concat(more, WEBP)),
                Arguments.of("every signature at byte 4", concat(new byte[4], PNG, JPEG, GIF_87A, GIF_89A, WEBP)));
    }

    @ParameterizedTest(name = "S5-AC-02 {0} is no format")
    @MethodSource("notOneOfTheFour")
    @DisplayName("S5-AC-02 other files, and a signature that is not at the first byte, are no format")
    void otherFilesAreNoFormat(String label, byte[] head) {
        assertThat(ImageFormat.of(head)).isEmpty();
    }

    // ---- for every input ----

    static Stream<Arguments> everyInput() {
        return Stream.of(
                        exactSignatures(),
                        signaturesWithMoreBytes(),
                        wholeFiles(),
                        jpegMarkers(),
                        webpLengths(),
                        tooShort(),
                        oneByteChanged(),
                        wrongGifs(),
                        wrongWebps(),
                        notOneOfTheFour())
                .flatMap(cases -> cases)
                .map(Arguments::get)
                .map(values -> Arguments.of(values[0], values[values.length - 1]));
    }

    @ParameterizedTest(name = "S5-AC-02 {0}: the array is as it was")
    @MethodSource("everyInput")
    @DisplayName("S5-AC-02 the given array is not changed")
    void givenArrayIsNotChanged(String label, byte[] head) {
        byte[] before = head.clone();

        ImageFormat.of(head);

        assertThat(head).isEqualTo(before);
    }

    @ParameterizedTest(name = "S5-AC-02 {0}: the answer is not null")
    @MethodSource("everyInput")
    @DisplayName("S5-AC-02 the answer is never null")
    void answerIsNeverNull(String label, byte[] head) {
        assertThat(ImageFormat.of(head)).isNotNull();
    }

    @ParameterizedTest(name = "S5-AC-02 {0}: asked twice, the answer is the same")
    @MethodSource("everyInput")
    @DisplayName("S5-AC-02 the same bytes give the same answer each time")
    void sameBytesGiveTheSameAnswer(String label, byte[] head) {
        assertThat(ImageFormat.of(head.clone())).isEqualTo(ImageFormat.of(head));
    }

    // ---- the constants ----

    @ParameterizedTest(name = "S5-AC-03 {0} is served as {1}")
    @CsvSource({"PNG, image/png", "JPEG, image/jpeg", "GIF, image/gif", "WEBP, image/webp"})
    @DisplayName("S5-AC-03 each format has its own Content-Type")
    void eachFormatHasItsContentType(ImageFormat format, String contentType) {
        assertThat(format.contentType()).isEqualTo(contentType);
    }

    @Test
    @DisplayName("S5-AC-02 the formats are PNG, JPEG, GIF, and WebP and no other")
    void formatsAreExactlyTheFour() {
        assertThat(ImageFormat.values())
                .containsExactlyInAnyOrder(ImageFormat.PNG, ImageFormat.JPEG, ImageFormat.GIF, ImageFormat.WEBP);
        assertThat(Arrays.stream(ImageFormat.values()).map(ImageFormat::contentType))
                .containsExactlyInAnyOrder("image/png", "image/jpeg", "image/gif", "image/webp");
    }

    @Test
    @DisplayName("S5-AC-02 HEAD_LENGTH is the 12 bytes of the longest signature")
    void headLengthIsTheLongestSignature() {
        assertThat(ImageFormat.HEAD_LENGTH).isEqualTo(12);
        assertThat(SIGNATURES).allSatisfy(s -> assertThat(s.bytes().length).isLessThanOrEqualTo(ImageFormat.HEAD_LENGTH));
        assertThat(WEBP).hasSize(ImageFormat.HEAD_LENGTH);
    }

    // ---- helpers ----

    private static byte[] hex(String spaced) {
        return HexFormat.of().parseHex(spaced.replace(" ", ""));
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[]... parts) {
        int length = Arrays.stream(parts).mapToInt(part -> part.length).sum();
        byte[] all = new byte[length];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, all, at, part.length);
            at += part.length;
        }
        return all;
    }
}
