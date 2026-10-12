package io.popdesk.ops;

import static io.popdesk.issue.IssueFlow.listRequest;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.AppLog.Mark;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The log after searching, filing, and editing issues (PII-2, S3 premortem 10): no form of a search
 * word or of an issue's title and content, on the paths that answer 200 and on those that answer
 * 400, 409, and 429. The forms are those of {@link LogForms}: as written, percent-encoded (the word
 * travels in the query string), and JSON-escaped.
 *
 * <p>It lives in this package for {@link LogForms}, which the log test of the first slice uses.
 */
class IssueSearchLogTest extends BareAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    /** A text with a quote, a slash, a space, and Korean, around two words of its own. */
    private static String text(String label, List<String> words) {
        String word = IssueFlow.word();
        String korean = IssueFlow.koreanWord();
        words.add(word);
        words.add(korean);
        String text = label + " \"" + word + "\" / " + korean;
        words.add(text);
        return text;
    }

    /**
     * Fails unless the log since {@code mark} has lines and holds no form of any of {@code values}.
     * A line of the test's own is written first and waited for, so the log read here is complete up
     * to this call.
     */
    private void assertNotLogged(Mark mark, List<String> values) {
        String probe = "issue-search-log-probe-" + Unique.number();
        LoggerFactory.getLogger("io.popdesk.ops.LogProbe").warn(probe);
        log.awaitLine(mark, probe);
        String since = log.since(mark);
        assertThat(since.lines().filter(line -> !line.isBlank()).count()).as("log lines since the mark").isPositive();
        assertThat(LogForms.foundIn(since, values)).as("search words and issue text in the log").isEmpty();
    }

    @Test
    @DisplayName("S3-AC-92 searching on both apps, filing, and a refused filing leave no form of the search word, the title, or the content in the log")
    void searchAndFilingLeaveNoText() {
        World w = flow.world();
        List<String> values = new ArrayList<>();
        Mark mark = log.mark();
        String title = text("Refund", values);
        String content = "First line\n" + text("**Card**", values) + " <b>&</b>";
        values.add(content);
        String q = text("Find", values);
        String refusedTitle = text("Refused", values) + " " + "x".repeat(200);
        values.add(refusedTitle);

        Issue issue = fixtures.fileIssue(w.reporter(), NewIssue.in(w.code()).title(title).content(content));
        for (Session session : List.of(w.reporter(), w.agent())) {
            listRequest(session, "q", q).expect(200);
            listRequest(session, "q", title).expect(200);
        }
        flow.detail(w.agent(), issue.key());
        Map<String, Object> refused = new LinkedHashMap<>();
        refused.put("companyCode", w.code());
        refused.put("title", refusedTitle);
        refused.put("content", content);
        w.reporter().client().post("/api/portal/issues").json(refused).expect(400);

        assertNotLogged(mark, values);
    }

    @Test
    @DisplayName("S3-AC-92 editing an issue, and searches and edits refused with 400, 409, and 429, leave no form of the text in the log")
    void editAndRefusedRequestsLeaveNoText() {
        World w = flow.world();
        List<String> values = new ArrayList<>();
        Mark mark = log.mark();
        Issue issue = fixtures.fileIssue(w.reporter(), NewIssue.in(w.code()).title(text("Before", values))
                .content(text("Was", values)));
        String editedContent = "Second line\n" + text("Now", values);
        values.add(editedContent);

        flow.edit(w.reporter(), issue.key(), Map.of("title", text("After", values), "content", editedContent));
        flow.edit(w.agent(), issue.key(), Map.of("title", text("By staff", values)));
        Map<String, Object> stale = new LinkedHashMap<>();
        stale.put("title", text("Stale", values));
        stale.put("version", flow.version(w.reporter(), issue.key()) - 1);
        ApiResponse conflict = w.reporter().client().patch("/api/portal/issues/" + issue.key()).json(stale).send();
        String tooLong = text("Too long", values) + " " + "x".repeat(100);
        values.add(tooLong);
        ApiResponse refusedSearch = listRequest(w.agent(), "q", tooLong).send();
        String limited = text("Limited", values);
        for (int i = 0; i < 60; i++) {
            listRequest(w.colleague(), "q", limited).expect(200);
        }
        ApiResponse overLimit = listRequest(w.colleague(), "q", limited).send();

        assertThat(conflict.status()).as(conflict.request()).isEqualTo(409);
        assertThat(conflict.errorCode()).isEqualTo("VERSION_CONFLICT");
        assertThat(refusedSearch.status()).as(refusedSearch.request()).isEqualTo(400);
        assertThat(overLimit.status()).as(overLimit.request()).isEqualTo(429);
        assertNotLogged(mark, values);
    }
}
