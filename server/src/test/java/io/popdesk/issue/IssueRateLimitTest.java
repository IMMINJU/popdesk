package io.popdesk.issue;

import static io.popdesk.issue.IssueSteps.items;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueSteps.Scene;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Per-account limits over 60 seconds: 10 issues, 30 comments (COM-8). The test clock stands still,
 * so every request of a test falls in one window until the test moves the clock.
 */
class IssueRateLimitTest extends BareAppTest {

    private IssueSteps steps;

    @BeforeEach
    void setUp() {
        steps = new IssueSteps(fixtures, clock);
    }

    private static ApiResponse file(Session customer, String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("companyCode", code);
        body.put("title", Unique.text("Rate"));
        return customer.client().post("/api/portal/issues").json(body).send();
    }

    private static ApiResponse comment(Session session, String key) {
        return session.client().post(session.api("/issues/" + key + "/comments"))
                .json(Map.of("content", Unique.text("Comment")))
                .send();
    }

    private static void assertRateLimited(ApiResponse response) {
        assertThat(response.status()).as(response.request()).isEqualTo(429);
        assertThat(response.errorCode()).isEqualTo("RATE_LIMITED");
        assertThat(response.header("Retry-After")).as("Retry-After").isNotBlank();
    }

    private static long issueCount(Session customer) {
        return customer.client().get("/api/portal/issues").query("size", 100).expect(200).json()
                .path("totalElements").asLong();
    }

    // S0-AC-50

    @Test
    @DisplayName("S0-AC-50 the 11th issue of one account within 60 seconds is 429 with Retry-After and is not filed")
    void eleventhIssueIsLimited() {
        Scene s = steps.scene();
        for (int i = 0; i < 10; i++) {
            file(s.customer(), s.code()).expectStatus(201);
        }

        ApiResponse eleventh = file(s.customer(), s.code());

        assertRateLimited(eleventh);
        assertThat(issueCount(s.customer())).isEqualTo(10);
    }

    @Test
    @DisplayName("S0-AC-50 filings are counted before the company is looked up: ten 404s use up the limit")
    void countedBeforeTheTargetIsFound() {
        Scene s = steps.scene();
        Company other = fixtures.createCompany(s.admin());
        for (int i = 0; i < 10; i++) {
            ApiResponse notFound = file(s.customer(), other.code());
            assertThat(notFound.status()).as(notFound.request()).isEqualTo(404);
        }

        ApiResponse eleventh = file(s.customer(), s.code());

        assertRateLimited(eleventh);
        assertThat(issueCount(s.customer())).isZero();
    }

    @Test
    @DisplayName("S0-AC-50 the limit is per account: a colleague can still file, and the limited account can after 60 seconds")
    void limitIsPerAccountAndWindow() {
        Scene s = steps.scene();
        Session colleague = fixtures.customer(s.admin(), s.code());
        for (int i = 0; i < 10; i++) {
            file(s.customer(), s.code()).expectStatus(201);
        }
        assertRateLimited(file(s.customer(), s.code()));

        ApiResponse byColleague = file(colleague, s.code());
        clock.advance(Duration.ofSeconds(61));
        ApiResponse afterWindow = file(s.customer(), s.code());

        assertThat(byColleague.status()).isEqualTo(201);
        assertThat(afterWindow.status()).isEqualTo(201);
        assertThat(items(s.customer().client().get("/api/portal/issues").query("size", 100).expect(200)))
                .hasSize(12);
    }

    // S0-AC-55

    @Test
    @DisplayName("S0-AC-55 the 31st comment of one account within 60 seconds is 429 with Retry-After and is not added")
    void thirtyFirstCommentIsLimited() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        for (int i = 0; i < 30; i++) {
            comment(s.customer(), issue.key()).expectStatus(201);
        }

        ApiResponse thirtyFirst = comment(s.customer(), issue.key());

        assertRateLimited(thirtyFirst);
        assertThat(s.customer().client().get("/api/portal/issues/" + issue.key() + "/comments")
                .query("size", 100).expect(200).json().path("totalElements").asLong()).isEqualTo(30);
    }

    @Test
    @DisplayName("S0-AC-55 comments are counted before the issue is looked up: thirty 404s use up the limit")
    void commentsCountedBeforeTheTargetIsFound() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        String missing = s.code() + "-999";
        for (int i = 0; i < 30; i++) {
            ApiResponse notFound = comment(s.customer(), missing);
            assertThat(notFound.status()).as(notFound.request()).isEqualTo(404);
        }

        ApiResponse thirtyFirst = comment(s.customer(), issue.key());

        assertRateLimited(thirtyFirst);
        assertThat(s.customer().client().get("/api/portal/issues/" + issue.key() + "/comments")
                .expect(200).json().path("totalElements").asLong()).isZero();
    }

    @Test
    @DisplayName("S0-AC-55 the comment limit is per account: staff on the same issue can still comment")
    void commentLimitIsPerAccount() {
        Scene s = steps.scene();
        Session agent = fixtures.agent(s.admin(), s.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        for (int i = 0; i < 30; i++) {
            comment(s.customer(), issue.key()).expectStatus(201);
        }
        assertRateLimited(comment(s.customer(), issue.key()));

        ApiResponse byAgent = comment(agent, issue.key());

        assertThat(byAgent.status()).isEqualTo(201);
    }
}
