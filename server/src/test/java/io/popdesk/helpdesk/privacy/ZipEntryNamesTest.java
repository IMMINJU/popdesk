package io.popdesk.helpdesk.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The paths inside a company export's ZIP (PII-8, S10B-AC-11, S10B-AC-14; acceptance.md 5절 6 and 25;
 * conventions.md "Company export"): {@code issues/{key}/files/{attachmentId}-{fileName}}, where {@code /},
 * {@code \}, control characters, every leading dot, and a dot followed by another dot become {@code _}, other
 * characters stay, and no path holds {@code ..}, a backslash, a control character, or starts with {@code /}.
 * One instance never gives the same path twice: {@code ~2}, {@code ~3} go after a path given already.
 */
class ZipEntryNamesTest {

    private static final String KEY = "ACME-12";
    private static final String ID = "6f1c2a9e-0b7d-4c55-9a1e-3d2f8b6c4e10";
    private static final String FILES = "issues/ACME-12/files/";

    static Stream<Arguments> names() {
        return Stream.of(
                Arguments.of("the conventions example ../x.txt", "../x.txt", "___x.txt"),
                Arguments.of("the conventions example .hidden", ".hidden", "_hidden"),
                Arguments.of("a plain name", "report.pdf", "report.pdf"),
                Arguments.of("dots inside a name", "a.b.c.tar.gz", "a.b.c.tar.gz"),
                Arguments.of("a dot at the end", "notes.", "notes."),
                Arguments.of("a slash", "a/b.txt", "a_b.txt"),
                Arguments.of("a backslash", "a\\b.txt", "a_b.txt"),
                Arguments.of("an absolute path", "/etc/passwd", "_etc_passwd"),
                Arguments.of("a Windows path", "C:\\Users\\x.txt", "C:_Users_x.txt"),
                Arguments.of("a Windows parent path", "..\\..\\x", "____._x"),
                Arguments.of("two dots inside", "a..b", "a_.b"),
                Arguments.of("three dots inside", "a...b", "a__.b"),
                Arguments.of("two dots at the end", "a..", "a_."),
                Arguments.of("a dot alone", ".", "_"),
                Arguments.of("two dots alone", "..", "__"),
                Arguments.of("three dots alone", "...", "___"),
                Arguments.of("leading dots, then dots inside", "..a..b", "__a_.b"),
                Arguments.of("a slash, then a dot that is not leading", "/.x", "_.x"),
                Arguments.of("a parent path in the middle", "a/../b", "a__._b"),
                Arguments.of("a dot, space, dot", "a. .b", "a. .b"),
                Arguments.of("NUL", "a\u0000b", "a_b"),
                Arguments.of("tab, line feed, carriage return", "a\tb\nc\rd", "a_b_c_d"),
                Arguments.of("U+001F, the last C0 control", "a\u001Fb", "a_b"),
                Arguments.of("U+007F DEL", "a\u007Fb", "a_b"),
                Arguments.of("U+0080, the first C1 control", "a\u0080b", "a_b"),
                Arguments.of("U+009F, the last C1 control", "a\u009Fb", "a_b"),
                Arguments.of("U+0020 space stays", " a b ", " a b "),
                Arguments.of("U+00A0 no-break space stays", "a\u00A0b", "a\u00A0b"),
                Arguments.of("Korean, symbols and an emoji stay", "견적서 (최종)~#%&:;*?\"<>|\uD83D\uDE00.pdf",
                        "견적서 (최종)~#%&:;*?\"<>|\uD83D\uDE00.pdf"),
                Arguments.of("a dash and the attachment ID form stay", ID, ID));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("names")
    @DisplayName("S10B-AC-11 /, \\, control characters, leading dots, and a dot before a dot become _; "
            + "other characters stay")
    void safe(String what, String name, String expected) {
        assertThat(ZipEntryNames.safe(name)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("S10B-AC-11 a null or empty name becomes _, so the name is never empty")
    void emptyName(String name) {
        assertThat(ZipEntryNames.safe(name)).isEqualTo("_");
    }

    @Test
    @DisplayName("S10B-AC-11 a safe name keeps its length: one character for one character")
    void sameLength() {
        String name = "../a\\b/.c..d\u0000e";
        assertThat(ZipEntryNames.safe(name)).hasSameSizeAs(name);
    }

    @Test
    @DisplayName("S10B-AC-11 the folder of an issue is issues/{key}/")
    void issue() {
        assertThat(ZipEntryNames.issue(KEY)).isEqualTo("issues/ACME-12/");
    }

    @Test
    @DisplayName("S10B-AC-11 the key is made safe too, so it opens no outside path")
    void unsafeKey() {
        assertThat(ZipEntryNames.issue("../x")).isEqualTo("issues/___x/");
        assertThat(ZipEntryNames.issue("a/b")).isEqualTo("issues/a_b/");
        assertThat(ZipEntryNames.issue("")).isEqualTo("issues/_/");
    }

    @Test
    @DisplayName("S10B-AC-11 an attachment's path is issues/{key}/files/{attachment ID}-{file name}")
    void file() {
        assertThat(new ZipEntryNames().file(KEY, ID, "report.pdf")).isEqualTo(FILES + ID + "-report.pdf");
    }

    @Test
    @DisplayName("S10B-AC-11 the file name in the path is made safe: ../x.txt becomes ___x.txt")
    void fileWithUnsafeName() {
        assertThat(new ZipEntryNames().file(KEY, ID, "../x.txt")).isEqualTo(FILES + ID + "-___x.txt");
        assertThat(new ZipEntryNames().file(KEY, ID, null)).isEqualTo(FILES + ID + "-_");
    }

    @Test
    @DisplayName("S10B-AC-11 the attachment ID is made safe too")
    void fileWithUnsafeId() {
        assertThat(new ZipEntryNames().file(KEY, "../id", "x.txt")).isEqualTo(FILES + "___id-x.txt");
    }

    @Test
    @DisplayName("PII-8 two files of the same name with different attachment IDs keep their own paths, "
            + "no number after them")
    void sameNameOtherId() {
        ZipEntryNames names = new ZipEntryNames();
        String other = "0a1b2c3d-0000-4000-8000-000000000001";

        assertThat(names.file(KEY, ID, "a.txt")).isEqualTo(FILES + ID + "-a.txt");
        assertThat(names.file(KEY, other, "a.txt")).isEqualTo(FILES + other + "-a.txt");
    }

    @Test
    @DisplayName("PII-8 the same file name in two issues keeps both paths, no number after them")
    void sameNameOtherIssue() {
        ZipEntryNames names = new ZipEntryNames();

        assertThat(names.file("ACME-1", ID, "a.txt")).isEqualTo("issues/ACME-1/files/" + ID + "-a.txt");
        assertThat(names.file("ACME-2", ID, "a.txt")).isEqualTo("issues/ACME-2/files/" + ID + "-a.txt");
    }

    @Test
    @DisplayName("PII-8 the same path asked again gets ~2, then ~3, then ~4")
    void samePathAgain() {
        ZipEntryNames names = new ZipEntryNames();

        assertThat(names.file(KEY, ID, "a.txt")).isEqualTo(FILES + ID + "-a.txt");
        assertThat(names.file(KEY, ID, "a.txt")).isEqualTo(FILES + ID + "-a.txt~2");
        assertThat(names.file(KEY, ID, "a.txt")).isEqualTo(FILES + ID + "-a.txt~3");
        assertThat(names.file(KEY, ID, "a.txt")).isEqualTo(FILES + ID + "-a.txt~4");
    }

    @Test
    @DisplayName("PII-8 two names that become the same once made safe get two paths")
    void sameOnceSafe() {
        ZipEntryNames names = new ZipEntryNames();

        assertThat(names.file(KEY, ID, "a/b")).isEqualTo(FILES + ID + "-a_b");
        assertThat(names.file(KEY, ID, "a\\b")).isEqualTo(FILES + ID + "-a_b~2");
        assertThat(names.file(KEY, ID, "a_b")).isEqualTo(FILES + ID + "-a_b~3");
    }

    @Test
    @DisplayName("PII-8 a number skips a path given already, also one an uploaded name made")
    void numberSkipsGivenPath() {
        ZipEntryNames names = new ZipEntryNames();

        assertThat(names.file(KEY, ID, "a.txt~2")).isEqualTo(FILES + ID + "-a.txt~2");
        assertThat(names.file(KEY, ID, "a.txt")).isEqualTo(FILES + ID + "-a.txt");
        assertThat(names.file(KEY, ID, "a.txt")).isEqualTo(FILES + ID + "-a.txt~3");
    }

    @Test
    @DisplayName("PII-8 a new instance is a new ZIP: it gives a path the other instance gave")
    void instancesApart() {
        new ZipEntryNames().file(KEY, ID, "a.txt");

        assertThat(new ZipEntryNames().file(KEY, ID, "a.txt")).isEqualTo(FILES + ID + "-a.txt");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "../../../etc/passwd", "..\\..\\windows\\system32", "/abs", "\\abs", ".", "..", "...", "....",
        ".../...", "a/../../b", "x\u0000/..", "\u0085..\u2028", "..\u0001..", "a.", "a..", ".-.", "~"})
    @DisplayName("S10B-AC-11 whatever name was uploaded, the path stays under issues/{key}/files/ and holds no "
            + "parent step, backslash, or control character")
    void hostileNamesStayInside(String name) {
        ZipEntryNames names = new ZipEntryNames();
        names.file(KEY, ID, name);
        String path = names.file(KEY, ID, name);

        assertThat(path).startsWith(FILES + ID + "-");
        assertThat(path).doesNotContain("..", "\\");
        assertThat(path).doesNotStartWith("/");
        assertThat(path.chars().filter(c -> c == '/').count()).isEqualTo(3);
        assertThat(path.chars().noneMatch(Character::isISOControl)).isTrue();
        assertThat(path.split("/")).doesNotContain("", ".", "..");
    }
}
