package io.popdesk.helpdesk.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

/**
 * The page an OIDC sign-in goes to after the callback (S11-AC-05; acceptance.md 5절 7; conventions.md "OIDC
 * sign-in"): {@code next} is kept only when it starts with {@code /}, does not start with {@code //} or
 * {@code /\}, holds no control characters, and is at most 512 characters; any other value is taken as {@code /}.
 */
class SafeNextTest {

    static Stream<Arguments> kept() {
        return Stream.of(
                Arguments.of("the home page", "/"),
                Arguments.of("a page", "/issues/ACME-12"),
                Arguments.of("a query and a fragment", "/issues?status=OPEN&page=2#top"),
                Arguments.of("two slashes later in the path", "/a//b"),
                Arguments.of("a backslash later in the path", "/a/\\b"),
                Arguments.of("an encoded slash after the first", "/%2F%2Fevil.example"),
                Arguments.of("an encoded backslash after the first", "/%5Cevil.example"),
                Arguments.of("an encoded line break, which is not a control character itself", "/a%0D%0Ab"),
                Arguments.of("a scheme later in the path", "/go?to=https://evil.example"),
                Arguments.of("a space", "/a b"),
                Arguments.of("Korean and an emoji", "/검색/😀"),
                Arguments.of("U+00A0 no-break space, not a control character", "/a b"),
                Arguments.of("a dot path", "/../x"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("kept")
    @DisplayName("S11-AC-05 a path of the same app is kept unchanged")
    void kept(String what, String next) {
        assertThat(SafeNext.of(next)).isEqualTo(next);
    }

    static Stream<Arguments> home() {
        return Stream.of(
                Arguments.of("another site without a scheme", "//evil.example"),
                Arguments.of("another site without a scheme, with a path", "//evil.example/login"),
                Arguments.of("three slashes", "///evil.example"),
                Arguments.of("a slash and a backslash", "/\\evil.example"),
                Arguments.of("a slash and two backslashes", "/\\\\evil.example"),
                Arguments.of("a backslash first", "\\evil.example"),
                Arguments.of("a backslash and a slash", "\\/evil.example"),
                Arguments.of("an https address", "https://evil.example/"),
                Arguments.of("an http address", "http://evil.example"),
                Arguments.of("a javascript address", "javascript:alert(1)"),
                Arguments.of("a data address", "data:text/html,x"),
                Arguments.of("a relative path", "issues/ACME-12"),
                Arguments.of("a dot path", "../x"),
                Arguments.of("a space before the slash", " /issues"),
                Arguments.of("an encoded first slash", "%2Fissues"),
                Arguments.of("a query only", "?x=1"),
                Arguments.of("NUL", "/a\u0000b"),
                Arguments.of("a tab", "/a\tb"),
                Arguments.of("a line feed", "/a\nb"),
                Arguments.of("a carriage return and line feed, a header split", "/a\r\nSet-Cookie: x=1"),
                Arguments.of("U+001F, the last C0 control", "/a\u001Fb"),
                Arguments.of("U+007F DEL", "/a\u007Fb"),
                Arguments.of("U+0080, the first C1 control", "/a\u0080b"),
                Arguments.of("U+009F, the last C1 control", "/a\u009Fb"),
                Arguments.of("a control character right after the slash", "/\u0001"),
                Arguments.of("a control character at the end", "/issues\n"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("home")
    @DisplayName("S11-AC-05 a value that is not a path of the same app is taken as /")
    void home(String what, String next) {
        assertThat(SafeNext.of(next)).isEqualTo("/");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("S11-AC-05 no next, or an empty one, is /")
    void missing(String next) {
        assertThat(SafeNext.of(next)).isEqualTo("/");
    }

    @Test
    @DisplayName("S11-AC-05 512 characters are kept, 513 are taken as /")
    void length() {
        String max = "/" + "a".repeat(511);
        String over = "/" + "a".repeat(512);

        assertThat(SafeNext.of(max)).isEqualTo(max);
        assertThat(SafeNext.of(over)).isEqualTo("/");
    }

    @Test
    @DisplayName("S11-AC-05 the length is counted in characters: 512 with emoji are kept, 513 are not")
    void lengthInCharacters() {
        String emoji = "😀";
        String max = "/" + emoji.repeat(511);
        String over = "/" + emoji.repeat(512);

        assertThat(SafeNext.of(max)).isEqualTo(max);
        assertThat(SafeNext.of(over)).isEqualTo("/");
    }
}
