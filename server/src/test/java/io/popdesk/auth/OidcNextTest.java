package io.popdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The page to go to after an OIDC sign-in (S11-AC-05): {@code next} of the start is kept only when it is a path of
 * the same app (starts with one {@code /}, not {@code //} or {@code /\}, no control character, at most 512
 * characters); any other value is taken as {@code /}. The callback answers 302 there with the new session.
 */
class OidcNextTest extends OidcAppTest {

    static Stream<Arguments> nexts() {
        String longest = "/" + "a".repeat(511);
        return Stream.of(
                Arguments.of("a path with a query", "/issues/X-1?tab=a", "/issues/X-1?tab=a"),
                Arguments.of("512 characters", longest, longest),
                Arguments.of("another host after //", "//evil.example", "/"),
                Arguments.of("a backslash after /", "/\\evil", "/"),
                Arguments.of("an absolute address", "https://evil.example", "/"),
                Arguments.of("a control character", "/issues/X-1\u0007", "/"),
                Arguments.of("a line break", "/issues\r\nX-Evil: 1", "/"),
                Arguments.of("513 characters", longest + "a", "/"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nexts")
    @DisplayName("S11-AC-05 the callback sends the browser to next when it is a path of the app, and to / for "
            + "another host, a backslash, an absolute address, a control character, or over 512 characters")
    void callbackGoesToNextOrHome(String what, String next, String expected) {
        Session admin = fixtures.signInAdmin();
        Session agent = person(admin, Role.AGENT);
        ApiClient browser = console();

        ApiResponse back = oidc.signIn(browser, next, OidcFlow.newSub(), oidc.claims(agent.email()));

        OidcFlow.assertRedirect(browser, back, expected);
        assertThat(browser.get("/api/console/me").expect(200).jsonLong("id")).as("account signed in")
                .isEqualTo(agent.accountId());
    }
}
