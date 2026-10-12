package io.popdesk.issue;

import static io.popdesk.issue.IssueFlow.listRequest;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Session;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The limit on lists with a search word: 60 for one account in 60 seconds, counted together over the
 * issue list, the account list, and the list of customers staff can file for (COM-8). The test clock
 * stands still, so every request of a test falls in one window until the test moves the clock. Each
 * test counts on accounts it made itself.
 */
class SearchRateLimitTest extends BareAppTest {

    private static final int LIMIT = 60;

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    private static ApiRequest issues(Session session) {
        return session.client().get(session.api("/issues"));
    }

    private static ApiRequest accounts(Session admin) {
        return admin.client().get("/api/console/accounts");
    }

    private static ApiRequest reporters(Session staff, String code) {
        return staff.client().get("/api/console/companies/" + code + "/reporters");
    }

    private static void assertRateLimited(ApiResponse response) {
        assertThat(response.status()).as(response.request()).isEqualTo(429);
        assertThat(response.errorCode()).isEqualTo("RATE_LIMITED");
        assertThat(response.header("Retry-After")).as("Retry-After").isNotBlank();
    }

    /** Sends {@code times} rounds of the three searches of an admin, each of which must answer 200. */
    private static void searchEachList(Session admin, String code, int times) {
        List<Supplier<ApiRequest>> lists = List.of(() -> issues(admin), () -> accounts(admin),
                () -> reporters(admin, code));
        for (int i = 0; i < times; i++) {
            for (Supplier<ApiRequest> list : lists) {
                list.get().query("q", IssueFlow.word()).expect(200);
            }
        }
    }

    @Test
    @DisplayName("S3-AC-15 the 61st search of one account within 60 seconds, over the issue list, the account list, and the reporter list together, is 429 with Retry-After")
    void sixtyFirstSearchOverThreeListsIsLimited() {
        World w = flow.world();
        Session admin = fixtures.admin(w.admin());
        searchEachList(admin, w.code(), LIMIT / 3);

        ApiResponse issueSearch = issues(admin).query("q", IssueFlow.word()).send();
        ApiResponse accountSearch = accounts(admin).query("q", IssueFlow.word()).send();
        ApiResponse reporterSearch = reporters(admin, w.code()).query("q", IssueFlow.word()).send();

        assertRateLimited(issueSearch);
        assertRateLimited(accountSearch);
        assertRateLimited(reporterSearch);
    }

    @Test
    @DisplayName("S3-AC-15 lists without q are not counted and still answer when the search limit is used up")
    void listsWithoutSearchAreNotCounted() {
        World w = flow.world();
        Session admin = fixtures.admin(w.admin());
        for (int i = 0; i < 5; i++) {
            issues(admin).expect(200);
            accounts(admin).expect(200);
            reporters(admin, w.code()).expect(200);
        }
        searchEachList(admin, w.code(), LIMIT / 3);
        assertRateLimited(issues(admin).query("q", IssueFlow.word()).send());

        assertThat(issues(admin).send().status()).as("issue list without q").isEqualTo(200);
        assertThat(issues(admin).query("status", "RECEIVED").send().status()).as("issue list with a filter, no q")
                .isEqualTo(200);
        assertThat(accounts(admin).send().status()).as("account list without q").isEqualTo(200);
        assertThat(reporters(admin, w.code()).send().status()).as("reporter list without q").isEqualTo(200);
    }

    @Test
    @DisplayName("S3-AC-15 the search limit is per account and window: a colleague can still search, and the limited customer can after 60 seconds")
    void limitIsPerAccountAndWindow() {
        World w = flow.world();
        for (int i = 0; i < LIMIT; i++) {
            listRequest(w.reporter(), "q", IssueFlow.word()).expect(200);
        }
        assertRateLimited(listRequest(w.reporter(), "q", IssueFlow.word()).send());

        ApiResponse byColleague = listRequest(w.colleague(), "q", IssueFlow.word()).send();
        ApiResponse byAgent = listRequest(w.agent(), "q", IssueFlow.word()).send();
        clock.advance(Duration.ofSeconds(61));
        ApiResponse afterWindow = listRequest(w.reporter(), "q", IssueFlow.word()).send();

        assertThat(byColleague.status()).as("a colleague's search").isEqualTo(200);
        assertThat(byAgent.status()).as("an agent's search").isEqualTo(200);
        assertThat(afterWindow.status()).as("the limited customer's search after 60 seconds").isEqualTo(200);
    }
}
