package io.popdesk.start;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * A start with {@code POPDESK_ISSUE_RETENTION} (S10B-AC-18): a period of days and hours of at least {@code P90D}
 * starts, anything else ends the start, unlike {@code POPDESK_AUDIT_RETENTION} ({@link AuditRetentionSettingTest}),
 * which keeps the process running (design.md 0절 9). "Does not start" is read as in {@link Boot}; what the start told
 * is its log and the messages of the failure ({@link StorageSteps#told}).
 *
 * <p>An app that starts with the cleanup on finds it on for the first time, so its first run leaves the first admin
 * an {@code ISSUE_RETENTION_ARMED} item (S10B-AC-19); the item's 90 days show that {@code PT2160H} is read as the
 * same length as {@code P90D}. A new installation has no issue, so the count is 0.
 */
class IssueRetentionSettingTest extends OwnStartTest {

    private static final String KEY = "POPDESK_ISSUE_RETENTION";

    /** The first run of the cleanup jobs comes within seconds of the start; allow a slow machine. */
    private static final Duration FIRST_RUN = Duration.ofSeconds(60);

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"P30D", "P89DT23H", "P1Y", "P3M", "abc"})
    @DisplayName("S10B-AC-18 with a POPDESK_ISSUE_RETENTION shorter than P90D, of years or months, or not a duration, "
            + "the app does not start and what it told names the key")
    void refusedValueEndsTheStart(String value) {
        Database db = postgres.newDatabase();
        try (Boot app = Boot.start(Settings.firstInstall(db).set(KEY, value))) {
            app.assertDidNotStart(KEY + "=" + value);

            assertThat(StorageSteps.linesWith(app, KEY)).as("what the start with %s=%s told about the key", KEY, value)
                    .isNotEmpty();
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"P90D", "PT2160H"})
    @DisplayName("S10B-AC-18, S10B-AC-19 with POPDESK_ISSUE_RETENTION of 90 days, written in days or in hours, the app "
            + "starts, the first admin signs in, and the first run leaves them an ISSUE_RETENTION_ARMED item with "
            + "retentionCount 0 and retentionDays 90")
    void ninetyDaysStarts(String value) {
        Database db = postgres.newDatabase();
        try (Boot app = Boot.start(Settings.firstInstall(db).set(KEY, value))) {
            app.assertStarted();
            assertReady(app);
            ApiClient admin = app.console();
            ApiResponse signIn = admin.post("/api/public/sign-in")
                    .json(Map.of("email", Settings.ADMIN_EMAIL, "password", Settings.ADMIN_PASSWORD)).send();
            assertThat(signIn.status()).as(signIn + " " + signIn.snippet()).isEqualTo(204);

            JsonNode item = Wait.until("an ISSUE_RETENTION_ARMED item of the first admin", FIRST_RUN, () -> {
                JsonNode items = admin.get("/api/console/notifications").query("size", 100).expect(200).json()
                        .path("items");
                return StreamSupport.stream(items.spliterator(), false)
                        .filter(n -> "ISSUE_RETENTION_ARMED".equals(n.path("kind").asString(null))).findFirst()
                        .orElse(null);
            });
            assertThat(item.path("retentionCount").asInt(-1)).as("retentionCount of %s", item).isZero();
            assertThat(item.path("retentionDays").asInt(-1)).as("retentionDays of %s", item).isEqualTo(90);
        }
    }
}
