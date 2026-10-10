package io.popdesk.auth;

import static io.popdesk.auth.AuthChecks.cookieValue;
import static io.popdesk.auth.AuthChecks.sessionCookies;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.AppTest;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Wait;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cleanup of ended sessions (OPS-2): a session that is past its 8 hours and is never used again
 * leaves the database when the cleanup runs. {@code ops.CleanupRecordTest} checks the count the job
 * records; here the session's row itself is gone.
 *
 * <p>The table of sessions is not named by the slice. The row of a session is a row holding its ID,
 * which the cookie carries as it is or in base64.
 */
class EndedSessionCleanupTest extends BareAppTest {

    @Test
    @DisplayName("S0-AC-82 the session cleanup deletes the row of a session that ended and was not used again")
    void endedSessionLeavesTheDatabase() {
        ApiResponse signedIn = AuthChecks.signIn(console(), AppTest.ADMIN_EMAIL, AppTest.ADMIN_PASSWORD,
                AuthChecks.freshIp());
        signedIn.expectStatus(204);
        assertThat(sessionCookies(signedIn)).as("session cookie in %s", signedIn.headers("Set-Cookie")).hasSize(1);
        List<String> ids = ids(cookieValue(sessionCookies(signedIn).getFirst()));
        assertThat(rows(ids)).as("rows holding the session ID right after sign-in, one of %s", ids).isNotEmpty();

        clock.advance(Duration.ofHours(25));

        Wait.untilAsserted("the cleanup deleted the ended session", Duration.ofSeconds(90),
                () -> assertThat(rows(ids)).as("rows holding the ID of a session that ended 17 hours ago").isEmpty());
    }

    /** The cookie value and, when it is base64, what it decodes to. */
    private static List<String> ids(String cookie) {
        List<String> out = new ArrayList<>(List.of(cookie));
        try {
            String decoded = new String(Base64.getDecoder().decode(cookie), StandardCharsets.UTF_8);
            if (decoded.matches("[A-Za-z0-9-]{16,}")) {
                out.add(decoded);
            }
        } catch (IllegalArgumentException notBase64) {
            // The cookie carries the ID as it is.
        }
        return out;
    }

    private List<Map<String, Object>> rows(List<String> ids) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : ids) {
            out.addAll(AuthChecks.rowsContaining(db, id));
        }
        return out;
    }
}
