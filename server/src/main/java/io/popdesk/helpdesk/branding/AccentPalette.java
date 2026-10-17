package io.popdesk.helpdesk.branding;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The values popdesk makes from one accent color, and their contrast (BRAND-3, S9-AC-11 to S9-AC-13, ADR D-50,
 * D-61, slice 9 design 3절). A pure calculation: no state, and the same color always gives the same values on any
 * JVM, since the cube roots and powers are {@link StrictMath}'s.
 *
 * <p>sRGB to OKLab and back are Björn Ottosson's formulas (2020); OKLCH is OKLab in polar form. A color moved to
 * another lightness keeps its hue and chroma; when that falls outside sRGB, the chroma alone is lowered by bisection
 * ({@value #BISECTIONS} steps, each linear channel within {@code [0, 1]} give or take {@value #GAMUT_MARGIN}) and
 * each channel is rounded to 8 bits. Contrast is the WCAG 2.1 contrast ratio of the rounded hex values.
 *
 * <table>
 * <caption>The eight values</caption>
 * <tr><th>value</th><th>light theme</th><th>dark theme</th></tr>
 * <tr><td>accent</td><td>the color as given</td><td>lightness {@code Ld = min(max(L + 0.265, 0.773), 0.97)}</td></tr>
 * <tr><td>accentHover</td><td>lightness {@code L - 0.08}, or {@code L + 0.2} when {@code L < 0.2}</td>
 *     <td>lightness {@code min(Ld + 0.08, 0.97)}</td></tr>
 * <tr><td>onAccent</td><td>{@code #FFFFFF}</td><td>{@code #191817}</td></tr>
 * <tr><td>accentSubtle</td><td>lightness 0.955, chroma {@code min(C, 0.035)}</td>
 *     <td>lightness 0.28, chroma {@code min(C, 0.05)}</td></tr>
 * </table>
 *
 * <p>0.265 and 0.773 are the lightness difference of the default emerald's two values ({@code #047857}, L 0.508, and
 * {@code #34D399}, L 0.773) and the dark one's lightness. A dark accent of lightness 0.773 or more has at least 6.89:1
 * over every dark background, so the dark check always passes for the values made here; it is kept all the same.
 *
 * <p>Cases for unit tests (design 3절): the values design measured ({@code #047857} gives light 4.81 and dark
 * {@code #6ECBA5} 7.27, hover {@code #005E43}/{@code #88E5BF}, subtle {@code #DBF8EB}/{@code #0A3123};
 * {@code #1E293B} gives 12.83 and dark {@code #A8B6CD}; {@code #E11D48} 4.10, not acceptable (4.70 over white alone);
 * {@code #C2410C} 4.50, acceptable; {@code #B45309} 4.38, not acceptable; {@code #FACC15} not acceptable;
 * {@code #6D28D9} 6.21, dark {@code #B8A5FF} 6.89), a dark value out of gamut (a saturated red or magenta such as
 * {@code #FF0000} or {@code #FF00FF}), lightness 0 and 1 ({@code #000000}, {@code #FFFFFF}), the {@code L < 0.2} branch
 * of the light hover ({@code #000000}, {@code #1E293B}), a contrast just under and just over 4.5 ({@code #B45309}
 * 4.38, {@code #C2410C} 4.504), small letters ({@code #e11d48} as {@code #E11D48}), and colors of the wrong form.
 */
public final class AccentPalette {

    /** The background color of the light theme; text on the light {@code accent}. */
    public static final String LIGHT_ON_ACCENT = "#FFFFFF";

    /** The background color of the dark theme; text on the dark {@code accent}. */
    public static final String DARK_ON_ACCENT = "#191817";

    /** The light backgrounds the light {@code accent} is text on: bg, surface, sunken, field (tokens.md). */
    static final List<String> LIGHT_BACKGROUNDS = List.of("#FFFFFF", "#F7F6F3", "#F1F0ED", "#F4F3F0");

    /** The dark backgrounds the dark {@code accent} is text on: bg, surface, sunken, field, and the chosen row. */
    static final List<String> DARK_BACKGROUNDS = List.of("#191817", "#201F1D", "#2A2826", "#121110", "#24221F");

    /** The lowest contrast a color must have in both themes to be taken (WCAG 2.1 AA for text). */
    public static final double MIN_CONTRAST = 4.5;

    static final int BISECTIONS = 30;
    static final double GAMUT_MARGIN = 1e-9;

    private static final Pattern HEX = Pattern.compile("#[0-9A-Fa-f]{6}");

    /** The four values of one theme, each {@code #RRGGBB} in capitals. */
    public record Theme(String accent, String accentHover, String onAccent, String accentSubtle) {}

    /**
     * What a color makes.
     *
     * @param lightContrast the lowest contrast of the light {@code accent} over {@link #LIGHT_BACKGROUNDS} and the
     *     light {@code accentSubtle}, rounded down to two decimals (4.499 is 4.49)
     * @param darkContrast the same for the dark {@code accent} over {@link #DARK_BACKGROUNDS} and the dark
     *     {@code accentSubtle}
     * @param acceptable both lowest contrasts, before rounding, are {@value #MIN_CONTRAST} or more
     */
    public record Palette(Theme light, Theme dark, double lightContrast, double darkContrast, boolean acceptable) {}

    private AccentPalette() {}

    /**
     * Whether {@code color} is {@code #} and six hexadecimal digits, in either letter case. Null, the empty text,
     * and anything else, spaces around it too, are not.
     */
    public static boolean valid(String color) {
        return color != null && HEX.matcher(color).matches();
    }

    /**
     * {@code color} in capitals, as stored ({@code #6d28d9} is {@code #6D28D9}).
     *
     * @throws IllegalArgumentException when it is not {@link #valid}
     */
    public static String normalized(String color) {
        if (!valid(color)) {
            throw new IllegalArgumentException("Not a #RRGGBB color");
        }
        return color.toUpperCase(Locale.ROOT);
    }

    /**
     * The eight values of {@code color}, their lowest contrasts, and whether a save takes the color.
     *
     * <p>Takes any {@link #valid} color, either letter case, and gives the same answer for both cases: black
     * ({@code #000000}, OKLab lightness 0) and white ({@code #FFFFFF}, lightness 1) too, which have no chroma and so
     * fit at any lightness. A value whose lightness and hue fall outside sRGB at the color's chroma gets less chroma
     * instead, never another lightness or hue. The light {@code accent} is the color in capitals; every other value
     * is computed. The contrasts are rounded down at the second decimal; {@code acceptable} is judged before that,
     * so a lowest contrast of 4.499 gives 4.49 and not acceptable, and one of exactly 4.5 is acceptable.
     *
     * @throws IllegalArgumentException when {@code color} is not {@link #valid}
     */
    public static Palette of(String color) {
        String light = normalized(color);
        double[] lab = oklab(light);
        double lightness = lab[0];
        double chroma = StrictMath.sqrt(lab[1] * lab[1] + lab[2] * lab[2]);
        double hue = StrictMath.atan2(lab[2], lab[1]);

        double darkLightness = Math.min(Math.max(lightness + 0.265, 0.773), 0.97);
        double lightHover = lightness < 0.2 ? lightness + 0.2 : lightness - 0.08;
        Theme lightTheme = new Theme(light, hex(lightHover, chroma, hue), LIGHT_ON_ACCENT,
                hex(0.955, Math.min(chroma, 0.035), hue));
        Theme darkTheme = new Theme(hex(darkLightness, chroma, hue), hex(Math.min(darkLightness + 0.08, 0.97), chroma,
                hue), DARK_ON_ACCENT, hex(0.28, Math.min(chroma, 0.05), hue));

        double lightLowest = lowest(lightTheme.accent(), LIGHT_BACKGROUNDS, lightTheme.accentSubtle());
        double darkLowest = lowest(darkTheme.accent(), DARK_BACKGROUNDS, darkTheme.accentSubtle());
        return new Palette(lightTheme, darkTheme, roundedDown(lightLowest), roundedDown(darkLowest),
                lightLowest >= MIN_CONTRAST && darkLowest >= MIN_CONTRAST);
    }

    /**
     * The WCAG 2.1 contrast ratio of two {@link #valid} colors, 1 to 21, the same whichever comes first.
     *
     * @throws IllegalArgumentException when either is not {@link #valid}
     */
    public static double contrast(String one, String other) {
        double a = luminance(normalized(one));
        double b = luminance(normalized(other));
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double lowest(String accent, List<String> backgrounds, String subtle) {
        double lowest = contrast(accent, subtle);
        for (String background : backgrounds) {
            lowest = Math.min(lowest, contrast(accent, background));
        }
        return lowest;
    }

    /** Down at the second decimal: 7.2798 is 7.27. */
    private static double roundedDown(double contrast) {
        return Math.floor(contrast * 100) / 100;
    }

    /** WCAG 2.1 relative luminance. */
    private static double luminance(String hex) {
        double[] rgb = channels(hex);
        return 0.2126 * linear(rgb[0]) + 0.7152 * linear(rgb[1]) + 0.0722 * linear(rgb[2]);
    }

    /** The three channels of {@code #RRGGBB}, each 0 to 1. */
    private static double[] channels(String hex) {
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            out[i] = Integer.parseInt(hex.substring(1 + 2 * i, 3 + 2 * i), 16) / 255.0;
        }
        return out;
    }

    /** sRGB transfer, encoded to linear. */
    private static double linear(double c) {
        return c <= 0.04045 ? c / 12.92 : StrictMath.pow((c + 0.055) / 1.055, 2.4);
    }

    /** sRGB transfer, linear to encoded. */
    private static double encoded(double c) {
        return c <= 0.0031308 ? 12.92 * c : 1.055 * StrictMath.pow(c, 1 / 2.4) - 0.055;
    }

    /** OKLab {@code L, a, b} of a color. */
    private static double[] oklab(String hex) {
        double[] rgb = channels(hex);
        double r = linear(rgb[0]);
        double g = linear(rgb[1]);
        double b = linear(rgb[2]);
        double l = StrictMath.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
        double m = StrictMath.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
        double s = StrictMath.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
        return new double[] {
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s};
    }

    /** Linear sRGB of an OKLCH color; channels may fall outside {@code [0, 1]}. */
    private static double[] linearRgb(double lightness, double chroma, double hue) {
        double a = chroma * StrictMath.cos(hue);
        double b = chroma * StrictMath.sin(hue);
        double l = cube(lightness + 0.3963377774 * a + 0.2158037573 * b);
        double m = cube(lightness - 0.1055613458 * a - 0.0638541728 * b);
        double s = cube(lightness - 0.0894841775 * a - 1.2914855480 * b);
        return new double[] {
            4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
            -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
            -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s};
    }

    private static double cube(double x) {
        return x * x * x;
    }

    private static boolean inGamut(double[] rgb) {
        for (double c : rgb) {
            if (c < -GAMUT_MARGIN || c > 1 + GAMUT_MARGIN) {
                return false;
            }
        }
        return true;
    }

    /** The OKLCH color as {@code #RRGGBB}, with less chroma when it does not fit sRGB. */
    private static String hex(double lightness, double chroma, double hue) {
        double[] rgb = linearRgb(lightness, chroma, hue);
        if (!inGamut(rgb)) {
            double low = 0;
            double high = chroma;
            for (int i = 0; i < BISECTIONS; i++) {
                double middle = (low + high) / 2;
                if (inGamut(linearRgb(lightness, middle, hue))) {
                    low = middle;
                } else {
                    high = middle;
                }
            }
            rgb = linearRgb(lightness, low, hue);
        }
        StringBuilder out = new StringBuilder("#");
        for (double c : rgb) {
            long channel = Math.round(Math.clamp(encoded(Math.clamp(c, 0.0, 1.0)), 0.0, 1.0) * 255);
            out.append(String.format(Locale.ROOT, "%02X", channel));
        }
        return out.toString();
    }
}
