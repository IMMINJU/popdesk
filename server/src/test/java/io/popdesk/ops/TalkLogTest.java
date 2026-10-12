package io.popdesk.ops;

import static io.popdesk.issue.TalkFlow.body;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.IssueFlow.World;
import io.popdesk.issue.TalkFlow;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.AppLog.Mark;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The log after comments are edited and deleted and people are mentioned (PII-2, TM-30, S4-AC-74): no
 * form of a comment's content, before or after an edit, of a mentioned person's name, or of the email
 * of anyone involved, on the paths that answer 2xx and on those that answer 400, 403, and 404. The
 * forms are those of {@link LogForms}: as written, percent-encoded ({@code %40}), and JSON-escaped.
 *
 * <p>The app has no SMTP here, so the mails of these comments are log lines; the masked address in
 * such a line is {@code notify.NoSmtpTalkMailLogTest}. It lives in this package for {@link LogForms}.
 */
class TalkLogTest extends BareAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    /** A content with a quote, a slash, a line break, and Korean around two words of its own. */
    private static String content(String label, List<String> values) {
        String word = IssueFlow.word();
        String korean = IssueFlow.koreanWord();
        String content = label + " \"" + word + "\" /\n" + korean;
        values.addAll(List.of(word, korean, content));
        return content;
    }

    /**
     * An agent assigned to the company whose name and email are words no other value has. Made before
     * the mark is taken: what inviting and signing in write is the business of the earlier log tests.
     */
    private Session namedAgent(World w, List<String> values) {
        String word = IssueFlow.word();
        String name = "Mentioned " + word;
        Session agent = fixtures.accept(fixtures.invite(w.admin(), Role.AGENT, word + "@example.test", name,
                List.of()));
        fixtures.assignAgent(w.admin(), w.code(), agent.accountId());
        values.addAll(List.of(word, name, agent.email()));
        return agent;
    }

    /**
     * Fails unless the log since {@code mark} has lines and holds no form of any of {@code values}.
     * A line of the test's own is written first and waited for, so the log read here is complete up
     * to this call.
     */
    private void assertNotLogged(Mark mark, List<String> values) {
        String probe = "talk-log-probe-" + Unique.number();
        LoggerFactory.getLogger("io.popdesk.ops.LogProbe").warn(probe);
        log.awaitLine(mark, probe);
        String since = log.since(mark);
        assertThat(since.lines().filter(line -> !line.isBlank()).count()).as("log lines since the mark").isPositive();
        assertThat(LogForms.foundIn(since, values)).as("comment text, names, and emails in the log").isEmpty();
    }

    @Test
    @DisplayName("S4-AC-74 adding, editing, mentioning in, and deleting comments leave no form of the old or the "
            + "new content, the mentioned agent's name, or anyone's email in the log")
    void editsMentionsAndDeletionsLeaveNoText() {
        World w = flow.world();
        List<String> values = new ArrayList<>(List.of(w.reporter().email(), w.agent().email()));
        Session mentioned = namedAgent(w, values);
        Session later = namedAgent(w, values);
        Mark mark = log.mark();
        String key = fixtures.fileIssue(w.reporter(), w.code()).key();

        Comment question = fixtures.comment(w.reporter(), key, content("Question", values));
        TalkFlow.editContent(w.reporter(), key, question.id(), content("Question, edited", values));
        Comment answer = TalkFlow.add(w.agent(), key, body("content", content("Answer", values), "mentions",
                List.of(mentioned.accountId(), w.reporter().accountId())));
        TalkFlow.edit(w.agent(), key, answer.id(), body("content", content("Answer, edited", values), "mentions",
                List.of(later.accountId())));
        Comment note = TalkFlow.add(w.agent(), key, body("content", content("Note", values), "internal", true,
                "mentions", List.of(mentioned.accountId())));
        TalkFlow.candidates(w.agent(), key, false);
        TalkFlow.delete(w.reporter(), key, question.id());
        TalkFlow.delete(w.admin(), key, answer.id());
        TalkFlow.delete(w.agent(), key, note.id());

        assertNotLogged(mark, values);
    }

    @Test
    @DisplayName("S4-AC-74 edits and mentions refused with 400 (empty and too long content, a mention that is "
            + "not allowed), 403, and 404 leave no form of the content, the mentioned people's names, or "
            + "anyone's email in the log")
    void refusedRequestsLeaveNoText() {
        World w = flow.world();
        List<String> values = new ArrayList<>(List.of(w.reporter().email(), w.agent().email(),
                w.colleague().email(), w.unassignedAgent().email()));
        Session mentioned = namedAgent(w, values);
        Mark mark = log.mark();
        String key = fixtures.fileIssue(w.reporter(), w.code()).key();
        Comment question = fixtures.comment(w.reporter(), key, content("Question", values));
        Comment answer = fixtures.comment(w.agent(), key, content("Answer", values));
        String tooLong = content("Too long", values) + " " + "x".repeat(4_000);
        values.add(tooLong);

        ApiResponse empty = TalkFlow.editRequest(w.reporter(), key, question.id(), body("content", "")).send();
        ApiResponse long1 = TalkFlow.editRequest(w.agent(), key, answer.id(), body("content", tooLong, "mentions",
                List.of(mentioned.accountId()))).send();
        ApiResponse badMentionOnAdd = TalkFlow.addRequest(w.agent(), key, body("content",
                content("Mentions a colleague", values), "mentions",
                List.of(mentioned.accountId(), w.colleague().accountId()))).send();
        ApiResponse badMentionOnEdit = TalkFlow.editRequest(w.agent(), key, answer.id(), body("content",
                content("Mentions nobody", values), "mentions", List.of(IssueFlow.NO_ACCOUNT))).send();
        ApiResponse notTheAuthor = TalkFlow.editRequest(w.colleague(), key, question.id(),
                body("content", content("Not mine", values))).send();
        ApiResponse notTheAuthorDeletes = TalkFlow.deleteRequest(w.agent(), key, question.id()).send();
        ApiResponse outOfScope = TalkFlow.editRequest(w.unassignedAgent(), key, answer.id(), body("content",
                content("Out of scope", values), "mentions", List.of(mentioned.accountId()))).send();
        ApiResponse noComment = TalkFlow.editRequest(w.reporter(), key, TalkFlow.NO_COMMENT,
                body("content", content("No comment", values))).send();
        ApiResponse hiddenCandidates = TalkFlow.candidatesRequest(w.unassignedAgent(), key, false).send();

        assertRefused(empty, 400, "INVALID_INPUT");
        assertRefused(long1, 400, "INVALID_INPUT");
        assertRefused(badMentionOnAdd, 400, "INVALID_INPUT");
        assertRefused(badMentionOnEdit, 400, "INVALID_INPUT");
        assertRefused(notTheAuthor, 403, "FORBIDDEN");
        assertRefused(notTheAuthorDeletes, 403, "FORBIDDEN");
        assertRefused(outOfScope, 404, "NOT_FOUND");
        assertRefused(noComment, 404, "NOT_FOUND");
        assertRefused(hiddenCandidates, 404, "NOT_FOUND");
        assertNotLogged(mark, values);
    }

    private static void assertRefused(ApiResponse response, int status, String code) {
        assertThat(response.status()).as(response.request() + ": " + response.snippet()).isEqualTo(status);
        assertThat(response.errorCode()).as("code of %s", response.request()).isEqualTo(code);
    }
}
