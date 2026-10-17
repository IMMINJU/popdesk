package io.popdesk.helpdesk.branding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.popdesk.helpdesk.branding.AccentPalette.Palette;
import io.popdesk.helpdesk.branding.AccentPalette.Theme;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The values popdesk makes from one accent color and their contrast (S9-AC-10 to S9-AC-13; ADR D-61; slice 9 design
 * 3절, 9절). Written from the Javadoc of {@link AccentPalette} and the design text alone. Values the design measured
 * are taken from it; the others were worked out with a separate implementation of the same formulas (Ottosson OKLab,
 * chroma bisection 30 steps within 1e-9, WCAG 2.1 contrast of the rounded hex, the lowest contrast rounded down).
 */
class AccentPaletteTest {

    private static final double EXACT = 1e-9;

    private static Theme theme(String accent, String hover, String onAccent, String subtle) {
        return new Theme(accent, hover, onAccent, subtle);
    }

    // ---- valid / normalized (S9-AC-10) ----

    @Test
    @DisplayName("S9-AC-10: # and six hex digits in either case are valid")
    void s9Ac10_validTakesHashAndSixHexDigitsInEitherCase() {
        assertThat(AccentPalette.valid("#047857")).isTrue();
        assertThat(AccentPalette.valid("#ABCDEF")).isTrue();
        assertThat(AccentPalette.valid("#abcdef")).isTrue();
        assertThat(AccentPalette.valid("#e11D48")).isTrue();
        assertThat(AccentPalette.valid("#000000")).isTrue();
        assertThat(AccentPalette.valid("#FFFFFF")).isTrue();
    }

    @Test
    @DisplayName("S9-AC-10: null, empty, spaces around, wrong length, missing # and non-hex are not valid")
    void s9Ac10_validRefusesEveryOtherForm() {
        assertThat(AccentPalette.valid(null)).isFalse();
        assertThat(AccentPalette.valid("")).isFalse();
        assertThat(AccentPalette.valid("#")).isFalse();
        assertThat(AccentPalette.valid(" #047857")).isFalse();
        assertThat(AccentPalette.valid("#047857 ")).isFalse();
        assertThat(AccentPalette.valid("047857")).isFalse();
        assertThat(AccentPalette.valid("#04785")).isFalse();
        assertThat(AccentPalette.valid("#0478570")).isFalse();
        assertThat(AccentPalette.valid("#04785G")).isFalse();
        assertThat(AccentPalette.valid("#FFF")).isFalse();
        assertThat(AccentPalette.valid("##047857")).isFalse();
        assertThat(AccentPalette.valid("rgb(4,120,87)")).isFalse();
        assertThat(AccentPalette.valid("#047857\n")).isFalse();
    }

    @Test
    @DisplayName("S9-AC-10: normalized gives capitals and leaves capitals as they are")
    void s9Ac10_normalizedGivesCapitals() {
        assertThat(AccentPalette.normalized("#6d28d9")).isEqualTo("#6D28D9");
        assertThat(AccentPalette.normalized("#e11d48")).isEqualTo("#E11D48");
        assertThat(AccentPalette.normalized("#AbCdEf")).isEqualTo("#ABCDEF");
        assertThat(AccentPalette.normalized("#047857")).isEqualTo("#047857");
    }

    @Test
    @DisplayName("S9-AC-10: normalized throws on a color that is not valid")
    void s9Ac10_normalizedThrowsOnBadForm() {
        assertThatThrownBy(() -> AccentPalette.normalized(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccentPalette.normalized("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccentPalette.normalized(" #047857")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccentPalette.normalized("#04785G")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccentPalette.normalized("047857")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("S9-AC-10: of throws on a color that is not valid")
    void s9Ac10_ofThrowsOnBadForm() {
        assertThatThrownBy(() -> AccentPalette.of(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccentPalette.of("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccentPalette.of("#FFF")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccentPalette.of("#0478577")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccentPalette.of("#047857 ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccentPalette.of("red")).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- constants ----

    @Test
    @DisplayName("onAccent constants are the two theme backgrounds; the minimum contrast is 4.5")
    void constantsAreTheThemeBackgroundsAndTheAaMinimum() {
        assertThat(AccentPalette.LIGHT_ON_ACCENT).isEqualTo("#FFFFFF");
        assertThat(AccentPalette.DARK_ON_ACCENT).isEqualTo("#191817");
        assertThat(AccentPalette.MIN_CONTRAST).isEqualTo(4.5);
        assertThat(AccentPalette.LIGHT_BACKGROUNDS).containsExactly("#FFFFFF", "#F7F6F3", "#F1F0ED", "#F4F3F0");
        assertThat(AccentPalette.DARK_BACKGROUNDS)
                .containsExactly("#191817", "#201F1D", "#2A2826", "#121110", "#24221F");
    }

    // ---- of: the values design measured (S9-AC-11, S9-AC-13) ----

    @Test
    @DisplayName("S9-AC-13: #047857 gives the measured eight values, 4.81 and 7.27 (7.2798 rounded down)")
    void s9Ac13_defaultEmeraldGivesMeasuredValues() {
        Palette p = AccentPalette.of("#047857");

        assertThat(p.light()).isEqualTo(theme("#047857", "#005E43", "#FFFFFF", "#DBF8EB"));
        assertThat(p.dark()).isEqualTo(theme("#6ECBA5", "#88E5BF", "#191817", "#0A3123"));
        assertThat(p.lightContrast()).isEqualTo(4.81);
        assertThat(p.darkContrast()).isEqualTo(7.27);
        assertThat(p.acceptable()).isTrue();
    }

    @Test
    @DisplayName("S9-AC-13: #1E293B gives 12.83 (12.836 rounded down) and dark #A8B6CD")
    void s9Ac13_navyGivesMeasuredValues() {
        Palette p = AccentPalette.of("#1E293B");

        // L 0.2795, so the light hover is L - 0.08.
        assertThat(p.light()).isEqualTo(theme("#1E293B", "#0C1627", "#FFFFFF", "#E8F1FF"));
        assertThat(p.dark()).isEqualTo(theme("#A8B6CD", "#C1D0E7", "#191817", "#1E293B"));
        assertThat(p.lightContrast()).isEqualTo(12.83);
        assertThat(p.darkContrast()).isEqualTo(7.13);
        assertThat(p.acceptable()).isTrue();
    }

    @Test
    @DisplayName("S9-AC-11: #E11D48 is 4.10 over the light group and not acceptable, though 4.70 over white alone")
    void s9Ac11_roseIsNotAcceptableOverTheLightGroup() {
        Palette p = AccentPalette.of("#E11D48");

        assertThat(p.light()).isEqualTo(theme("#E11D48", "#BD0037", "#FFFFFF", "#FFEBEB"));
        assertThat(p.dark()).isEqualTo(theme("#FFB9BA", "#FFDFDF", "#191817", "#3E1E20"));
        assertThat(p.lightContrast()).isEqualTo(4.10);
        assertThat(p.darkContrast()).isEqualTo(9.02);
        assertThat(p.acceptable()).isFalse();
        assertThat(AccentPalette.contrast("#E11D48", "#FFFFFF")).isCloseTo(4.697112144822063, within(EXACT));
    }

    @Test
    @DisplayName("S9-AC-11: #C2410C is 4.504, shown 4.50, acceptable (just over the line)")
    void s9Ac11_orangeJustOverTheLineIsAcceptable() {
        Palette p = AccentPalette.of("#C2410C");

        assertThat(p.light()).isEqualTo(theme("#C2410C", "#9F3100", "#FFFFFF", "#FFEBE5"));
        assertThat(p.dark()).isEqualTo(theme("#FFAC91", "#FFD2C4", "#191817", "#3E2016"));
        assertThat(p.lightContrast()).isEqualTo(4.50);
        assertThat(p.darkContrast()).isEqualTo(8.08);
        assertThat(p.acceptable()).isTrue();
    }

    @Test
    @DisplayName("S9-AC-11: #B45309 is 4.38, not acceptable (just under the line)")
    void s9Ac11_amberJustUnderTheLineIsNotAcceptable() {
        Palette p = AccentPalette.of("#B45309");

        assertThat(p.light()).isEqualTo(theme("#B45309", "#934100", "#FFFFFF", "#FFECE2"));
        assertThat(p.dark()).isEqualTo(theme("#FFAE83", "#FFD4BD", "#191817", "#3D2111"));
        assertThat(p.lightContrast()).isEqualTo(4.38);
        assertThat(p.darkContrast()).isEqualTo(8.13);
        assertThat(p.acceptable()).isFalse();
    }

    @Test
    @DisplayName("S9-AC-11: #FACC15 is not acceptable; its dark lightness is capped at 0.97")
    void s9Ac11_yellowIsNotAcceptable() {
        Palette p = AccentPalette.of("#FACC15");

        assertThat(p.light()).isEqualTo(theme("#FACC15", "#DCB300", "#FFFFFF", "#F8F0D6"));
        // L 0.861 + 0.265 is over 0.97, so the dark accent and its hover both sit at 0.97.
        assertThat(p.dark()).isEqualTo(theme("#FFF5D6", "#FFF5D6", "#191817", "#322807"));
        assertThat(p.lightContrast()).isEqualTo(1.34);
        assertThat(p.darkContrast()).isEqualTo(13.37);
        assertThat(p.acceptable()).isFalse();
    }

    @Test
    @DisplayName("S9-AC-13: #6D28D9 is 6.21, dark #B8A5FF with its lowest 6.89")
    void s9Ac13_violetGivesMeasuredValues() {
        Palette p = AccentPalette.of("#6D28D9");

        assertThat(p.light()).isEqualTo(theme("#6D28D9", "#5700B7", "#FFFFFF", "#F0EEFF"));
        assertThat(p.dark()).isEqualTo(theme("#B8A5FF", "#D0C6FF", "#191817", "#2A243F"));
        assertThat(p.lightContrast()).isEqualTo(6.21);
        assertThat(p.darkContrast()).isEqualTo(6.89);
        assertThat(p.acceptable()).isTrue();
    }

    // ---- boundary: acceptable is judged before rounding down ----

    @Test
    @DisplayName("S9-AC-11: a lowest contrast of 4.49996 shows 4.49 and is not acceptable")
    void s9Ac11_lowestJustUnderFourAndAHalfShowsFourFortyNineAndIsRefused() {
        Palette p = AccentPalette.of("#784EEA");

        // The lowest is over the made subtle #EFEEFF: 4.499959...
        assertThat(p.light()).isEqualTo(theme("#784EEA", "#6331CE", "#FFFFFF", "#EFEEFF"));
        assertThat(AccentPalette.contrast("#784EEA", "#EFEEFF")).isCloseTo(4.499959183767002, within(EXACT));
        assertThat(p.lightContrast()).isEqualTo(4.49);
        assertThat(p.darkContrast()).isEqualTo(8.38);
        assertThat(p.acceptable()).isFalse();
    }

    @Test
    @DisplayName("S9-AC-11: a lowest contrast of 4.500008 shows 4.50 and is acceptable")
    void s9Ac11_lowestJustOverFourAndAHalfIsAcceptable() {
        Palette p = AccentPalette.of("#D80351");

        // The lowest is over the made subtle #FFEAEC: 4.5000077...
        assertThat(p.light()).isEqualTo(theme("#D80351", "#B00040", "#FFFFFF", "#FFEAEC"));
        assertThat(AccentPalette.contrast("#D80351", "#FFEAEC")).isCloseTo(4.500007683367458, within(EXACT));
        assertThat(p.lightContrast()).isEqualTo(4.50);
        assertThat(p.darkContrast()).isEqualTo(8.32);
        assertThat(p.acceptable()).isTrue();
    }

    // ---- out of gamut ----

    @Test
    @DisplayName("S9-AC-13: pure red's dark values fall outside sRGB and get less chroma at the same lightness")
    void s9Ac13_redOutOfGamutKeepsLightnessAndHue() {
        Palette p = AccentPalette.of("#FF0000");

        // L 0.628, dark L 0.893 and hover 0.973 capped to 0.97; the chroma 0.258 does not fit there.
        assertThat(p.light()).isEqualTo(theme("#FF0000", "#D50000", "#FFFFFF", "#FFEBE8"));
        assertThat(p.dark()).isEqualTo(theme("#FFCFC6", "#FFF2EF", "#191817", "#3E1F1A"));
        assertThat(p.lightContrast()).isEqualTo(3.48);
        assertThat(p.darkContrast()).isEqualTo(10.48);
        assertThat(p.acceptable()).isFalse();
    }

    @Test
    @DisplayName("S9-AC-13: magenta's dark values fall outside sRGB and get less chroma at the same lightness")
    void s9Ac13_magentaOutOfGamutKeepsLightnessAndHue() {
        Palette p = AccentPalette.of("#FF00FF");

        assertThat(p.light()).isEqualTo(theme("#FF00FF", "#D900D9", "#FFFFFF", "#FEE8FC"));
        assertThat(p.dark()).isEqualTo(theme("#FFEEFE", "#FFF0FE", "#191817", "#362035"));
        assertThat(p.lightContrast()).isEqualTo(2.71);
        assertThat(p.darkContrast()).isEqualTo(13.22);
        assertThat(p.acceptable()).isFalse();
    }

    // ---- lightness 0 and 1, the L < 0.2 hover branch ----

    @Test
    @DisplayName("S9-AC-13: black (L 0) takes the L + 0.2 hover branch and a gray dark accent at L 0.773")
    void s9Ac13_blackTakesTheLightenedHoverBranch() {
        Palette p = AccentPalette.of("#000000");

        assertThat(p.light()).isEqualTo(theme("#000000", "#161616", "#FFFFFF", "#F0F0F0"));
        assertThat(p.dark()).isEqualTo(theme("#B5B5B5", "#CFCFCF", "#191817", "#292929"));
        assertThat(p.lightContrast()).isEqualTo(18.42);
        assertThat(p.darkContrast()).isEqualTo(7.09);
        assertThat(p.acceptable()).isTrue();
    }

    @Test
    @DisplayName("S9-AC-13: a dark navy under L 0.2 takes the L + 0.2 hover; one just over 0.2 takes L - 0.08")
    void s9Ac13_hoverBranchesEitherSideOfLightnessPointTwo() {
        // #0B1120 has L 0.180: hover is lighter than the accent.
        Palette under = AccentPalette.of("#0B1120");
        assertThat(under.light()).isEqualTo(theme("#0B1120", "#3A4254", "#FFFFFF", "#EAF0FF"));
        assertThat(under.dark()).isEqualTo(theme("#ACB5CB", "#C5CFE5", "#191817", "#212939"));
        assertThat(under.lightContrast()).isEqualTo(16.50);
        assertThat(under.darkContrast()).isEqualTo(7.09);
        assertThat(under.acceptable()).isTrue();

        // #0F172A has L 0.208: hover is darker than the accent.
        Palette over = AccentPalette.of("#0F172A");
        assertThat(over.light()).isEqualTo(theme("#0F172A", "#020616", "#FFFFFF", "#E9F0FF"));
        assertThat(over.dark()).isEqualTo(theme("#A9B5CF", "#C2CFEA", "#191817", "#1F283D"));
        assertThat(over.lightContrast()).isEqualTo(15.61);
        assertThat(over.darkContrast()).isEqualTo(7.13);
        assertThat(over.acceptable()).isTrue();
    }

    @Test
    @DisplayName("S9-AC-11: white (L 1) is 1.00 over white and not acceptable; its dark values are capped at 0.97")
    void s9Ac11_whiteIsNotAcceptable() {
        Palette p = AccentPalette.of("#FFFFFF");

        assertThat(p.light()).isEqualTo(theme("#FFFFFF", "#E4E4E4", "#FFFFFF", "#F0F0F0"));
        assertThat(p.dark()).isEqualTo(theme("#F5F5F5", "#F5F5F5", "#191817", "#292929"));
        assertThat(p.lightContrast()).isEqualTo(1.00);
        assertThat(p.darkContrast()).isEqualTo(13.34);
        assertThat(p.acceptable()).isFalse();
    }

    // ---- small letters, same answer each time ----

    @Test
    @DisplayName("S9-AC-10: small letters give the same palette as capitals, light accent in capitals")
    void s9Ac10_smallLettersGiveTheSamePalette() {
        Palette lower = AccentPalette.of("#e11d48");

        assertThat(lower).isEqualTo(AccentPalette.of("#E11D48"));
        assertThat(lower.light().accent()).isEqualTo("#E11D48");
        assertThat(lower.lightContrast()).isEqualTo(4.10);
        assertThat(AccentPalette.of("#6d28d9").dark().accent()).isEqualTo("#B8A5FF");
    }

    @Test
    @DisplayName("S9-AC-13: the same color always gives the same values")
    void s9Ac13_sameInputSameValues() {
        assertThat(AccentPalette.of("#047857")).isEqualTo(AccentPalette.of("#047857"));
        assertThat(AccentPalette.of("#FF00FF")).isEqualTo(AccentPalette.of("#ff00ff"));
    }

    // ---- contrast ----

    @Test
    @DisplayName("contrast is the WCAG 2.1 ratio: 21 for black on white, 1 for a color on itself")
    void contrastIsTheWcagRatio() {
        assertThat(AccentPalette.contrast("#000000", "#FFFFFF")).isCloseTo(21.0, within(EXACT));
        assertThat(AccentPalette.contrast("#047857", "#047857")).isCloseTo(1.0, within(EXACT));
        assertThat(AccentPalette.contrast("#047857", "#FFFFFF")).isCloseTo(5.483924052498799, within(EXACT));
        assertThat(AccentPalette.contrast("#191817", "#FFFFFF")).isCloseTo(17.731086814737093, within(EXACT));
        // Not rounded down: the raw ratio behind the 7.27 of #047857's dark accent.
        assertThat(AccentPalette.contrast("#6ECBA5", "#0A3123")).isCloseTo(7.279843910469683, within(EXACT));
    }

    @Test
    @DisplayName("contrast is the same whichever color comes first, and takes small letters")
    void contrastIsSymmetricAndTakesSmallLetters() {
        assertThat(AccentPalette.contrast("#FFFFFF", "#E11D48"))
                .isEqualTo(AccentPalette.contrast("#E11D48", "#FFFFFF"));
        assertThat(AccentPalette.contrast("#191817", "#6ECBA5"))
                .isEqualTo(AccentPalette.contrast("#6ECBA5", "#191817"));
        assertThat(AccentPalette.contrast("#e11d48", "#ffffff")).isCloseTo(4.697112144822063, within(EXACT));
    }

    @Test
    @DisplayName("contrast throws when either color is not valid")
    void contrastThrowsOnBadForm() {
        assertThatThrownBy(() -> AccentPalette.contrast("#FFF", "#000000"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccentPalette.contrast("#000000", "000000"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccentPalette.contrast(null, "#000000"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccentPalette.contrast("#000000", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
