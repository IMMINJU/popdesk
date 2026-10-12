package io.popdesk.issue;

import static io.popdesk.issue.TalkFlow.body;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * Editing and deleting a comment the caller cannot see, or one that is not there, is 404 and changes
 * nothing: an internal comment for a customer, another company's issue, an issue of a company the
 * agent is not assigned to, a colleague's private issue, a comment ID of another issue, an ID nobody
 * has, and an ID that is no number.
 */
class CommentNotFoundTest extends BareAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    /** Why the caller gets 404. */
    enum Hidden {
        INTERNAL_COMMENT_FOR_THE_REPORTER,
        ISSUE_OF_ANOTHER_COMPANY_FOR_A_CUSTOMER,
        ISSUE_FOR_AN_AGENT_OF_NO_COMPANY,
        ISSUE_FOR_AN_AGENT_OF_ANOTHER_COMPANY,
        PRIVATE_ISSUE_FOR_A_COLLEAGUE,
        COMMENT_OF_ANOTHER_ISSUE_THE_CALLER_SEES,
        ID_OF_NO_COMMENT,
        ID_THAT_IS_NO_NUMBER;

        /** Whether the answer must be, byte for byte, the one for an ID of no comment. */
        boolean sameBodyAsNoComment() {
            return this != ID_THAT_IS_NO_NUMBER;
        }
    }

    /** One try: who asks, with which issue key and comment ID. */
    private record Attempt(Session caller, String key, Object commentId) {}

    @ParameterizedTest(name = "{0}")
    @EnumSource(Hidden.class)
    @DisplayName("S4-AC-03 editing and deleting a comment the caller cannot see, of another issue, or that is not there is 404 NOT_FOUND, like an ID of no comment, and no comment changes")
    void hiddenOrMissingCommentIsNotFound(Hidden hidden) {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        Issue second = fixtures.fileIssue(w.reporter(), w.code());
        Issue privateIssue = fixtures.fileIssue(w.reporter(), NewIssue.in(w.code()).privateIssue());
        Comment byReporter = fixtures.comment(w.reporter(), issue.key(), Unique.text("By the reporter"));
        Comment internal = fixtures.internalComment(w.agent(), issue.key(), Unique.text("Internal"));
        Comment onPrivate = fixtures.comment(w.reporter(), privateIssue.key(), Unique.text("On the private issue"));
        fixtures.comment(w.reporter(), second.key(), Unique.text("On the second issue"));
        Attempt attempt = switch (hidden) {
            case INTERNAL_COMMENT_FOR_THE_REPORTER -> new Attempt(w.reporter(), issue.key(), internal.id());
            case ISSUE_OF_ANOTHER_COMPANY_FOR_A_CUSTOMER -> new Attempt(
                    fixtures.customer(w.admin(), w.elsewhere().code()), issue.key(), byReporter.id());
            case ISSUE_FOR_AN_AGENT_OF_NO_COMPANY -> new Attempt(w.unassignedAgent(), issue.key(), byReporter.id());
            case ISSUE_FOR_AN_AGENT_OF_ANOTHER_COMPANY -> new Attempt(w.agentElsewhere(), issue.key(),
                    byReporter.id());
            case PRIVATE_ISSUE_FOR_A_COLLEAGUE -> new Attempt(w.colleague(), privateIssue.key(), onPrivate.id());
            case COMMENT_OF_ANOTHER_ISSUE_THE_CALLER_SEES -> new Attempt(w.reporter(), second.key(),
                    byReporter.id());
            case ID_OF_NO_COMMENT -> new Attempt(w.reporter(), issue.key(), TalkFlow.NO_COMMENT);
            case ID_THAT_IS_NO_NUMBER -> new Attempt(w.reporter(), issue.key(), "abc");
        };
        // The reporter's own comment can be edited and deleted, so a 404 below is about the caller or the ID.
        Comment spare = fixtures.comment(w.reporter(), issue.key(), Unique.text("Spare"));
        TalkFlow.editContent(w.reporter(), issue.key(), spare.id(), Unique.text("Spare, edited"));
        TalkFlow.delete(w.reporter(), issue.key(), spare.id());
        Map<String, List<JsonNode>> before = commentsOf(w.admin(), issue, second, privateIssue);
        String path = TalkFlow.commentPath(attempt.caller(), attempt.key(), attempt.commentId());

        Session caller = attempt.caller();
        List<ApiResponse> noComment = List.of(
                TalkFlow.editRequest(caller, attempt.key(), TalkFlow.NO_COMMENT, body("content", "Changed")).send(),
                TalkFlow.deleteRequest(caller, attempt.key(), TalkFlow.NO_COMMENT).send());
        List<ApiResponse> noIssue = List.of(
                TalkFlow.editRequest(caller, IssueFlow.NO_KEY, TalkFlow.NO_COMMENT, body("content", "Changed"))
                        .send(),
                TalkFlow.deleteRequest(caller, IssueFlow.NO_KEY, TalkFlow.NO_COMMENT).send());
        List<ApiResponse> tried = List.of(
                caller.client().patch(path).json(body("content", "Changed")).send(),
                caller.client().delete(path).send());

        for (int i = 0; i < tried.size(); i++) {
            for (ApiResponse answer : List.of(noComment.get(i), noIssue.get(i), tried.get(i))) {
                assertThat(answer.status()).as(answer.request() + ": " + answer.snippet()).isEqualTo(404);
                assertThat(answer.errorCode()).as("code of %s", answer.request()).isEqualTo("NOT_FOUND");
            }
            assertThat(noComment.get(i).text()).as("body of %s, like for an issue key nobody has",
                    noComment.get(i).request()).isEqualTo(noIssue.get(i).text());
            if (hidden.sameBodyAsNoComment()) {
                assertThat(tried.get(i).text()).as("body of %s, like for an ID of no comment",
                        tried.get(i).request()).isEqualTo(noComment.get(i).text());
            }
        }
        assertThat(commentsOf(w.admin(), issue, second, privateIssue)).as("the comments of the three issues")
                .isEqualTo(before);
    }

    private static Map<String, List<JsonNode>> commentsOf(Session admin, Issue... issues) {
        Map<String, List<JsonNode>> out = new LinkedHashMap<>();
        for (Issue issue : issues) {
            out.put(issue.key(), TalkFlow.comments(admin, issue.key()));
        }
        return out;
    }
}
