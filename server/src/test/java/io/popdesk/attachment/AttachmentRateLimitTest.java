package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.bytes;
import static io.popdesk.attachment.AttachmentSteps.list;
import static io.popdesk.attachment.AttachmentSteps.upload;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.AttachmentSteps.Scene;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 30 uploads per account in 60 seconds (COM-8), measured with the app's {@code Clock}. The test
 * clock stands still, so every upload of a test falls in one window until the test moves it.
 */
class AttachmentRateLimitTest extends BareAppTest {

    private AttachmentSteps steps;

    @BeforeEach
    void setUp() {
        steps = new AttachmentSteps(fixtures);
    }

    @Test
    @DisplayName("S0-AC-65 the 31st upload of one account within 60 seconds is 429 RATE_LIMITED with Retry-After and is not stored")
    void thirtyFirstUploadIsLimited() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        for (int i = 0; i < 30; i++) {
            upload(s.customer(), issue.key(), "file-" + i + ".txt", bytes(10, i)).expectStatus(201);
        }

        ApiResponse thirtyFirst = upload(s.customer(), issue.key(), "file-30.txt", bytes(10, 30));

        assertRateLimited(thirtyFirst);
        assertThat(list(s.customer(), issue.key())).hasSize(30);
    }

    @Test
    @DisplayName("S0-AC-65 uploads are counted before the issue is looked up: thirty 404s use up the limit")
    void countedBeforeTheIssueIsFound() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        String missing = s.code() + "-999";
        for (int i = 0; i < 30; i++) {
            ApiResponse notFound = upload(s.customer(), missing, "file-" + i + ".txt", bytes(10, i));
            assertThat(notFound.status()).as(notFound.request()).isEqualTo(404);
        }

        ApiResponse thirtyFirst = upload(s.customer(), issue.key(), "file-30.txt", bytes(10, 30));

        assertRateLimited(thirtyFirst);
        assertThat(list(s.customer(), issue.key())).isEmpty();
    }

    @Test
    @DisplayName("S0-AC-65 the limit is per account and per 60 seconds of the app clock")
    void limitIsPerAccountAndWindow() {
        Scene s = steps.scene();
        Session agent = fixtures.agent(s.admin(), s.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        for (int i = 0; i < 30; i++) {
            upload(s.customer(), issue.key(), "file-" + i + ".txt", bytes(10, i)).expectStatus(201);
        }
        assertRateLimited(upload(s.customer(), issue.key(), "limited.txt", bytes(10, 31)));

        ApiResponse byAgent = upload(agent, issue.key(), "agent.txt", bytes(10, 32));
        clock.advance(Duration.ofSeconds(61));
        ApiResponse afterWindow = upload(s.customer(), issue.key(), "after.txt", bytes(10, 33));

        assertThat(byAgent.status()).isEqualTo(201);
        assertThat(afterWindow.status()).isEqualTo(201);
        assertThat(list(s.customer(), issue.key())).hasSize(32);
    }

    private static void assertRateLimited(ApiResponse response) {
        assertThat(response.status()).as(response.request()).isEqualTo(429);
        assertThat(response.errorCode()).isEqualTo("RATE_LIMITED");
        assertThat(response.header("Retry-After")).as("Retry-After").isNotBlank();
    }
}
