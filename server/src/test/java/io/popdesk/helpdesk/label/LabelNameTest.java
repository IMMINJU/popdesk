package io.popdesk.helpdesk.label;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.ApiError;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * How a label name is trimmed, put in NFC, measured, and keyed (LBL-1, S6A-AC-02, S6A-AC-03). Written
 * from the Javadoc of {@link LabelName} alone. Every provider below gives a label first.
 */
class LabelNameTest {

    private static final String FIELD = "name";

    private static final String E_ACUTE_NFD = "é";
    private static final String E_ACUTE_NFC = "é";
    private static final String HANGUL_NFC = "한글"; // 한글
    private static final String HANGUL_NFD = Normalizer.normalize(HANGUL_NFC, Normalizer.Form.NFD);
    private static final String EMOJI = "😀"; // U+1F600, two chars, one code point

    // ---- names that are taken ----

    static Stream<Arguments> takenNames() {
        return Stream.of(
                Arguments.of("spaces around", "  Trim me  ", "Trim me"),
                Arguments.of("tabs and line ends around", "\t Billing \r\n", "Billing"),
                Arguments.of("ideographic spaces around", "　Billing　", "Billing"),
                Arguments.of("spaces inside are kept", "a  b", "a  b"),
                Arguments.of("one character", "a", "a"),
                Arguments.of("one character with spaces around", "  a  ", "a"),
                Arguments.of("NFD accent", "Café", "Café"),
                Arguments.of("NFD Hangul", HANGUL_NFD, HANGUL_NFC),
                Arguments.of("NFD Hangul with spaces around", " " + HANGUL_NFD + " ", HANGUL_NFC),
                Arguments.of("NFC Hangul", HANGUL_NFC, HANGUL_NFC),
                Arguments.of("Turkish capital I with dot", "İstanbul", "İstanbul"),
                Arguments.of("29 characters", "a".repeat(29), "a".repeat(29)),
                Arguments.of("30 characters", "a".repeat(30), "a".repeat(30)),
                Arguments.of("30 characters with spaces around", "   " + "a".repeat(30) + "   ", "a".repeat(30)),
                Arguments.of("60 code points that are 30 in NFC", E_ACUTE_NFD.repeat(30), E_ACUTE_NFC.repeat(30)),
                Arguments.of("30 Hangul syllables sent as NFD", HANGUL_NFD.repeat(15), HANGUL_NFC.repeat(15)),
                Arguments.of("30 emoji, 60 chars", EMOJI.repeat(30), EMOJI.repeat(30)),
                Arguments.of("29 letters and an emoji", "a".repeat(29) + EMOJI, "a".repeat(29) + EMOJI));
    }

    @ParameterizedTest(name = "S6A-AC-02 {0} is taken")
    @MethodSource("takenNames")
    @DisplayName("S6A-AC-02 a name of 1 to 30 code points after strip and NFC is taken as trimmed NFC")
    void nameIsTrimmedAndPutInNfc(String label, String received, String stored) {
        List<ApiError.FieldError> errors = new ArrayList<>();

        LabelName name = LabelName.of(FIELD, received, errors);

        assertThat(errors).isEmpty();
        assertThat(name).isNotNull();
        assertThat(name.name()).isEqualTo(stored);
        assertThat(Normalizer.isNormalized(name.name(), Normalizer.Form.NFC)).isTrue();
        assertThat(name.name().codePointCount(0, name.name().length())).isBetween(1, LabelName.MAX);
        assertThat(name.key()).isEqualTo(LabelName.key(stored));
    }

    @Test
    @DisplayName("S6A-AC-02 a name that is taken adds nothing to errors that already hold an entry")
    void takenNameLeavesOtherErrors() {
        ApiError.FieldError other = new ApiError.FieldError("color", "INVALID_FORMAT");
        List<ApiError.FieldError> errors = new ArrayList<>(List.of(other));

        LabelName name = LabelName.of(FIELD, "  Billing  ", errors);

        assertThat(name).isEqualTo(new LabelName("Billing", "billing"));
        assertThat(errors).containsExactly(other);
    }

    @Test
    @DisplayName("S6A-AC-02 MAX is 30")
    void maxIsThirty() {
        assertThat(LabelName.MAX).isEqualTo(30);
    }

    // ---- names that are not taken ----

    static Stream<Arguments> refusedNames() {
        return Stream.of(
                Arguments.of("null", null, "REQUIRED"),
                Arguments.of("the empty text", "", "REQUIRED"),
                Arguments.of("one space", " ", "BLANK"),
                Arguments.of("spaces, tab, and line end", "  \t\n ", "BLANK"),
                Arguments.of("an ideographic space", "　", "BLANK"),
                Arguments.of("31 characters", "a".repeat(31), "TOO_LONG"),
                Arguments.of("31 characters with spaces around", "  " + "a".repeat(31) + "  ", "TOO_LONG"),
                Arguments.of("40 characters", "a".repeat(40), "TOO_LONG"),
                Arguments.of("31 code points in NFC sent as 62 in NFD", E_ACUTE_NFD.repeat(31), "TOO_LONG"),
                Arguments.of("31 Hangul syllables sent as NFD", HANGUL_NFD.repeat(15) + Normalizer.normalize(
                        "가", Normalizer.Form.NFD), "TOO_LONG"),
                Arguments.of("31 emoji", EMOJI.repeat(31), "TOO_LONG"),
                Arguments.of("30 letters and an emoji", "a".repeat(30) + EMOJI, "TOO_LONG"),
                Arguments.of("a NUL inside", "a\u0000b", "INVALID_FORMAT"),
                Arguments.of("a NUL alone", "\u0000", "INVALID_FORMAT"),
                Arguments.of("an unpaired high surrogate inside", "a\uD800b", "INVALID_FORMAT"),
                Arguments.of("an unpaired high surrogate at the end", "abc\uD83D", "INVALID_FORMAT"),
                Arguments.of("an unpaired low surrogate", "\uDE00abc", "INVALID_FORMAT"));
    }

    @ParameterizedTest(name = "S6A-AC-02 {0} is {2}")
    @MethodSource("refusedNames")
    @DisplayName("S6A-AC-02 a name that is not 1 to 30 code points gives null and one error for the field")
    void refusedNameGivesOneError(String label, String received, String code) {
        List<ApiError.FieldError> errors = new ArrayList<>();

        LabelName name = LabelName.of(FIELD, received, errors);

        assertThat(name).isNull();
        assertThat(errors).containsExactly(new ApiError.FieldError(FIELD, code));
    }

    @ParameterizedTest(name = "S6A-AC-02 {0} adds one entry after the one already there")
    @MethodSource("refusedNames")
    @DisplayName("S6A-AC-02 a refused name adds exactly one entry and keeps the ones already there")
    void refusedNameAddsOneEntry(String label, String received, String code) {
        ApiError.FieldError other = new ApiError.FieldError("color", "INVALID_FORMAT");
        List<ApiError.FieldError> errors = new ArrayList<>(List.of(other));

        LabelName.of("title", received, errors);

        assertThat(errors).containsExactly(other, new ApiError.FieldError("title", code));
    }

    // ---- the key ----

    @ParameterizedTest(name = "S6A-AC-03 \"{0}\" and \"{1}\" have the same key")
    @CsvSource(value = {
            "Billing|BILLING",
            "Billing|billing",
            "bIlLiNg|BiLlInG",
            "Café|CAFÉ",
            "Café|café",
            "CAFÉ|Café",
            "İ|i̇",
            "STRASSE|strasse",
            "Straße|STRAẞE",
            "한글|한글"},
            delimiter = '|', ignoreLeadingAndTrailingWhitespace = false)
    @DisplayName("S6A-AC-03 names that differ only in case or in normal form have the same key")
    void caseAndNormalFormDoNotChangeTheKey(String one, String other) {
        assertThat(LabelName.key(one)).isEqualTo(LabelName.key(other));
    }

    @ParameterizedTest(name = "S6A-AC-03 \"{0}\" and \"{1}\" have different keys")
    @CsvSource(value = {
            "Billing|Billing ",
            "Billing| Billing",
            "Billing|Bill ing",
            "Billing|Billings",
            "I|ı",
            "İ|ı",
            "Straße|STRASSE",
            "Café|Cafe"},
            delimiter = '|', ignoreLeadingAndTrailingWhitespace = false)
    @DisplayName("S6A-AC-03 whitespace is kept in the key, and other letters are other names")
    void otherNamesHaveOtherKeys(String one, String other) {
        assertThat(LabelName.key(one)).isNotEqualTo(LabelName.key(other));
    }

    @ParameterizedTest(name = "S6A-AC-03 the key of \"{0}\" is \"{1}\"")
    @CsvSource(value = {
            "Billing|billing",
            "BILLING|billing",
            "Billing |billing ",
            " a | a ",
            "I|i",
            "İ|i̇",
            "İstanbul|i̇stanbul",
            "Café|café",
            "CAFÉ|café",
            "Straße|straße",
            "STRASSE|strasse",
            "ẞ|ß",
            "한글|한글"},
            delimiter = '|', ignoreLeadingAndTrailingWhitespace = false)
    @DisplayName("S6A-AC-03 the key is the name in NFC, then in lower case by Locale.ROOT")
    void keyIsNfcInRootLowerCase(String name, String key) {
        assertThat(LabelName.key(name)).isEqualTo(key);
    }

    @Test
    @DisplayName("S6A-AC-03 the key of a Hangul name sent as NFD is the NFC name")
    void keyOfNfdHangulIsNfc() {
        assertThat(LabelName.key(HANGUL_NFD)).isEqualTo(HANGUL_NFC);
    }

    @Test
    @DisplayName("S6A-AC-03 the key does not depend on the server's language")
    void keyDoesNotDependOnDefaultLocale() {
        Locale before = Locale.getDefault();
        try {
            for (Locale language : List.of(Locale.forLanguageTag("tr-TR"), Locale.forLanguageTag("az"),
                    Locale.forLanguageTag("lt"), Locale.ENGLISH, Locale.KOREAN)) {
                Locale.setDefault(language);

                assertThat(LabelName.key("I")).as("I in %s", language).isEqualTo("i");
                assertThat(LabelName.key("İ")).as("İ in %s", language).isEqualTo("i̇");
                assertThat(LabelName.key("BILLING")).as("BILLING in %s", language).isEqualTo("billing");
                assertThat(LabelName.key("İstanbul")).as("İstanbul in %s", language)
                        .isEqualTo("i̇stanbul")
                        .doesNotContain("ı");
            }
        } finally {
            Locale.setDefault(before);
        }
    }
}
