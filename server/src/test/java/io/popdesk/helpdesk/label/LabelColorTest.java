package io.popdesk.helpdesk.label;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Which label colors are taken and how they are stored (LBL-1, S6A-AC-01, S6A-AC-04). Written from the
 * Javadoc of {@link LabelColor} alone.
 */
class LabelColorTest {

    @Test
    @DisplayName("S6A-AC-01 a label created without a color is gray #808080")
    void missingColorIsGray() {
        assertThat(LabelColor.DEFAULT).isEqualTo("#808080");
        assertThat(LabelColor.parse(null)).contains("#808080");
    }

    @ParameterizedTest(name = "S6A-AC-04 \"{0}\" is stored as \"{1}\"")
    @CsvSource({
        "#a1b2c3, #A1B2C3",
        "#abcdef, #ABCDEF",
        "#aBcDeF, #ABCDEF",
        "#ABCDEF, #ABCDEF",
        "#A1B2C3, #A1B2C3",
        "#123456, #123456",
        "#000000, #000000",
        "#ffffff, #FFFFFF",
        "#FFFFFF, #FFFFFF",
        "#808080, #808080",
        "#0f0F0f, #0F0F0F"
    })
    @DisplayName("S6A-AC-04 # and six hexadecimal digits are taken, with the letters in capitals")
    void sixHexDigitsAreTakenInCapitals(String received, String stored) {
        assertThat(LabelColor.parse(received)).contains(stored);
    }

    @ParameterizedTest(name = "S6A-AC-04 \"{0}\" is not taken")
    @ValueSource(strings = {
        "",
        "123456", "a1b2c3", "0x123456", "##123456", "123456#",
        "#", "#1", "#123", "#abc", "#1234", "#12345", "#1234567", "#12345678", "#ABCDEF00",
        "#GGGGGG", "#gggggg", "#ABCDEG", "#abcdeg", "#12345z", "#-12345", "#+12345",
        "red", "gray", "rgb(1,2,3)",
        " #123456", "#123456 ", "#12 456", "\t#123456", "#123456\n", " ",
        "#１２３４５６", "#١٢٣٤٥٦"
    })
    @DisplayName("S6A-AC-04 no #, another number of digits, non-hex letters, names, and whitespace are not taken")
    void otherValuesAreRefused(String received) {
        assertThat(LabelColor.parse(received)).isNotNull().isEmpty();
    }
}
