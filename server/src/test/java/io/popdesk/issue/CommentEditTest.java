package io.popdesk.issue;

import static io.popdesk.issue.IssueSteps.fieldCode;
import static io.popdesk.issue.IssueSteps.fieldNames;
import static io.popdesk.issue.IssueSteps.text;
import static io.popdesk.issue.TalkFlow.assertRefused;
import static io.popdesk.issue.TalkFlow.body;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/**
 * Editing a comment: only its author may, the content keeps the rules of adding, the time of the
 * edit, what a closed issue, a deleted comment, and a reporter who left the company get, and that an
 * edit never moves a comment between public and internal.
 */
class CommentEditTest extends BareAppTest {

    /** An emoji outside the BMP: one code point, two UTF-16 units. */
    private static final String EMOJI = "😀";

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    /** Who wrote the comment, and where. */
    enum Author {
        REPORTER_ON_THE_PORTAL,
        AGENT_IN_PUBLIC,
        AGENT_INTERNALLY;

        Session session(World w) {
            return this == REPORTER_ON_THE_PORTAL ? w.reporter() : w.agent();
        }

        Comment write(Fixtures fixtures, World w, String key, String content) {
            return this == AGENT_INTERNALLY
                    ? fixtures.internalComment(w.agent(), key, content)
                    : fixtures.comment(session(w), key, content);
        }

        /** Everyone who sees the comment. */
        List<Session> readers(World w) {
            return this == AGENT_INTERNALLY
                    ? List.of(w.agent(), w.admin())
                    : List.of(w.agent(), w.admin(), w.reporter());
        }
    }

    // S4-AC-01

    @ParameterizedTest(name = "{0}")
    @EnumSource(Author.class)
    @DisplayName("S4-AC-01 the author changes the content: 200 with the new content and the time of the edit, and everyone who sees the comment reads the same")
    void authorChangesTheContent(Author author) {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        Comment written = author.write(fixtures, w, issue.key(), Unique.text("Before"));
        Instant editedAt = clock.advance(Duration.ofMinutes(3));
        String next = Unique.text("After");

        JsonNode edited = TalkFlow.editContent(author.session(w), issue.key(), written.id(), next);

        assertThat(edited.path("id").asLong(-1)).isEqualTo(written.id());
        assertThat(text(edited, "content")).isEqualTo(next);
        assertThat(IssueFlow.time(edited, "editedAt")).as("editedAt in the response").isEqualTo(editedAt);
        assertThat(text(edited, "createdAt")).as("createdAt stays").isEqualTo(text(written.json(), "createdAt"));
        for (Session reader : author.readers(w)) {
            JsonNode listed = TalkFlow.comment(reader, issue.key(), written.id());
            assertThat(text(listed, "content")).as("content for %s", reader.role()).isEqualTo(next);
            assertThat(IssueFlow.time(listed, "editedAt")).as("editedAt for %s", reader.role()).isEqualTo(editedAt);
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Author.class, names = {"REPORTER_ON_THE_PORTAL", "AGENT_IN_PUBLIC"})
    @DisplayName("S4-AC-01 a content of 1 code point and one of 4,000 code points are accepted and kept whole")
    void contentLengthLimitsInside(Author author) {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        Comment written = author.write(fixtures, w, issue.key(), Unique.text("Before"));
        String longest = EMOJI.repeat(4_000);

        JsonNode shortest = TalkFlow.editContent(author.session(w), issue.key(), written.id(), "a");
        JsonNode longestEdit = TalkFlow.editContent(author.session(w), issue.key(), written.id(), longest);

        assertThat(text(shortest, "content")).isEqualTo("a");
        assertThat(text(longestEdit, "content")).isEqualTo(longest);
        assertThat(text(TalkFlow.comment(w.admin(), issue.key(), written.id()), "content")).isEqualTo(longest);
    }

    static Stream<Arguments> refusedContents() {
        return Stream.of(
                Arguments.of("empty", "", "REQUIRED"),
                Arguments.of("only whitespace", "   \n  ", "BLANK"),
                Arguments.of("4,001 code points", "a".repeat(4_001), "TOO_LONG"),
                Arguments.of("null", null, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedContents")
    @DisplayName("S4-AC-01 an empty content, one of only whitespace, one of 4,001 code points, and null are 400 INVALID_INPUT naming content, on both apps, and the comment stays")
    void contentOutsideTheLimitsIsRefused(String what, String content, String fieldCode) {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        Comment byReporter = fixtures.comment(w.reporter(), issue.key(), Unique.text("By the reporter"));
        Comment byAgent = fixtures.comment(w.agent(), issue.key(), Unique.text("By the agent"));
        // An edit that works, so the refusals below are about the content alone.
        TalkFlow.editContent(w.agent(), issue.key(), byAgent.id(), Unique.text("Edited once"));
        List<JsonNode> before = TalkFlow.comments(w.admin(), issue.key());

        ApiResponse onPortal = TalkFlow.editRequest(w.reporter(), issue.key(), byReporter.id(),
                body("content", content)).send();
        ApiResponse onConsole = TalkFlow.editRequest(w.agent(), issue.key(), byAgent.id(),
                body("content", content)).send();

        for (ApiResponse refused : List.of(onPortal, onConsole)) {
            assertRefused(refused, 400, "INVALID_INPUT");
            assertThat(fieldNames(refused)).as("fields of %s", refused.request()).contains("content");
            if (fieldCode != null) {
                assertThat(fieldCode(refused, "content")).as("content in %s", refused.request()).isEqualTo(fieldCode);
            }
        }
        assertThat(TalkFlow.comments(w.admin(), issue.key())).as("the comments after the refused edits")
                .isEqualTo(before);
    }

    // S4-AC-02

    /** Someone who sees a comment they did not write. */
    enum Intruder {
        AGENT_ON_ANOTHER_AGENTS_COMMENT,
        ADMIN_ON_AN_AGENTS_COMMENT,
        ADMIN_ON_THE_REPORTERS_COMMENT,
        AGENT_ON_THE_REPORTERS_COMMENT,
        REPORTER_ON_A_STAFF_COMMENT,
        COLLEAGUE_ON_THE_REPORTERS_COMMENT
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Intruder.class)
    @DisplayName("S4-AC-02 someone who sees a comment but did not write it gets 403 FORBIDDEN on editing it, an admin too, and the comment stays")
    void onlyTheAuthorEdits(Intruder intruder) {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        Session caller;
        Comment target;
        switch (intruder) {
            case AGENT_ON_ANOTHER_AGENTS_COMMENT -> {
                caller = fixtures.agent(w.admin(), w.code());
                target = fixtures.comment(w.agent(), issue.key(), Unique.text("By an agent"));
            }
            case ADMIN_ON_AN_AGENTS_COMMENT -> {
                caller = w.admin();
                target = fixtures.internalComment(w.agent(), issue.key(), Unique.text("By an agent"));
            }
            case ADMIN_ON_THE_REPORTERS_COMMENT -> {
                caller = w.admin();
                target = fixtures.comment(w.reporter(), issue.key(), Unique.text("By the reporter"));
            }
            case AGENT_ON_THE_REPORTERS_COMMENT -> {
                caller = w.agent();
                target = fixtures.comment(w.reporter(), issue.key(), Unique.text("By the reporter"));
            }
            case REPORTER_ON_A_STAFF_COMMENT -> {
                caller = w.reporter();
                target = fixtures.comment(w.agent(), issue.key(), Unique.text("By an agent"));
            }
            default -> {
                caller = w.colleague();
                target = fixtures.comment(w.reporter(), issue.key(), Unique.text("By the reporter"));
            }
        }
        assertThat(TalkFlow.find(caller, issue.key(), target.id())).as("the comment as the caller sees it")
                .isNotNull();
        JsonNode before = TalkFlow.comment(w.admin(), issue.key(), target.id());
        clock.advance(Duration.ofMinutes(1));

        ApiResponse refused = TalkFlow.editRequest(caller, issue.key(), target.id(),
                body("content", Unique.text("Not mine"))).send();

        assertRefused(refused, 403, "FORBIDDEN");
        assertThat(TalkFlow.comment(w.admin(), issue.key(), target.id())).as("the comment after the refused edit")
                .isEqualTo(before);
    }

    // S4-AC-04

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Author.class, names = {"REPORTER_ON_THE_PORTAL", "AGENT_IN_PUBLIC"})
    @DisplayName("S4-AC-04 the author's edit on a CLOSED issue is 400 ISSUE_CLOSED and the comment stays")
    void closedIssueRefusesEdits(Author author) {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        Comment written = author.write(fixtures, w, issue.key(), Unique.text("Before closing"));
        // The same edit works while the issue is open.
        TalkFlow.editContent(author.session(w), issue.key(), written.id(), Unique.text("While open"));
        flow.close(w.reporter(), issue.key());
        JsonNode before = TalkFlow.comment(w.admin(), issue.key(), written.id());

        ApiResponse refused = TalkFlow.editRequest(author.session(w), issue.key(), written.id(),
                body("content", Unique.text("After closing"))).send();

        assertRefused(refused, 400, "ISSUE_CLOSED");
        assertThat(TalkFlow.comment(w.admin(), issue.key(), written.id())).as("the comment after the refused edit")
                .isEqualTo(before);
    }

    // S4-AC-05

    @Test
    @DisplayName("S4-AC-05 a reporter the admin took out of the company gets 403 FORBIDDEN on editing and on deleting their own comment, in the same session, and still reads it")
    void reporterWhoLeftTheCompanyMayNotEditOrDelete() {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        Comment own = fixtures.comment(w.reporter(), issue.key(), Unique.text("While a member"));
        // Both work for a member.
        TalkFlow.editContent(w.reporter(), issue.key(), own.id(), Unique.text("Edited as a member"));
        Comment deleted = fixtures.comment(w.reporter(), issue.key(), Unique.text("Deleted as a member"));
        TalkFlow.delete(w.reporter(), issue.key(), deleted.id());
        JsonNode before = TalkFlow.comment(w.reporter(), issue.key(), own.id());
        flow.removeCustomer(w.admin(), w.code(), w.reporter().accountId());

        ApiResponse edit = TalkFlow.editRequest(w.reporter(), issue.key(), own.id(),
                body("content", Unique.text("After leaving"))).send();
        ApiResponse delete = TalkFlow.deleteRequest(w.reporter(), issue.key(), own.id()).send();

        assertRefused(edit, 403, "FORBIDDEN");
        assertRefused(delete, 403, "FORBIDDEN");
        assertThat(TalkFlow.comment(w.reporter(), issue.key(), own.id())).as("the comment the former member reads")
                .isEqualTo(before);
    }

    // S4-AC-06

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Author.class, names = {"REPORTER_ON_THE_PORTAL", "AGENT_IN_PUBLIC"})
    @DisplayName("S4-AC-06 the content the comment already has is 200 and leaves editedAt as it was, null before the first change and the first time after it; an empty body is 200 and changes nothing")
    void sameContentKeepsTheEditTime(Author author) {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        Session session = author.session(w);
        String first = Unique.text("First");
        Comment written = author.write(fixtures, w, issue.key(), first);
        clock.advance(Duration.ofMinutes(1));

        JsonNode sameAsWritten = TalkFlow.editContent(session, issue.key(), written.id(), first);

        assertThat(text(sameAsWritten, "content")).isEqualTo(first);
        assertThat(IssueFlow.time(sameAsWritten, "editedAt")).as("editedAt after the same content").isNull();

        Instant editedAt = clock.advance(Duration.ofMinutes(1));
        String second = Unique.text("Second");
        JsonNode changed = TalkFlow.editContent(session, issue.key(), written.id(), second);
        clock.advance(Duration.ofMinutes(1));
        JsonNode sameAgain = TalkFlow.editContent(session, issue.key(), written.id(), second);
        clock.advance(Duration.ofMinutes(1));
        JsonNode emptyBody = TalkFlow.edit(session, issue.key(), written.id(), body());

        assertThat(IssueFlow.time(changed, "editedAt")).as("editedAt after a new content").isEqualTo(editedAt);
        for (JsonNode unchanged : List.of(sameAgain, emptyBody, TalkFlow.comment(w.admin(), issue.key(),
                written.id()))) {
            assertThat(text(unchanged, "content")).isEqualTo(second);
            assertThat(IssueFlow.time(unchanged, "editedAt")).as("editedAt in %s", unchanged).isEqualTo(editedAt);
        }
    }

    // S4-AC-07

    @Test
    @DisplayName("S4-AC-07 a comment that was never edited carries editedAt as null, in the response of adding it and in the lists of both apps")
    void neverEditedCommentHasNullEditedAt() {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        Comment byReporter = fixtures.comment(w.reporter(), issue.key(), Unique.text("By the reporter"));
        Comment byAgent = fixtures.comment(w.agent(), issue.key(), Unique.text("By the agent"));

        List<JsonNode> views = List.of(byReporter.json(), byAgent.json(),
                TalkFlow.comment(w.reporter(), issue.key(), byReporter.id()),
                TalkFlow.comment(w.reporter(), issue.key(), byAgent.id()),
                TalkFlow.comment(w.agent(), issue.key(), byReporter.id()),
                TalkFlow.comment(w.agent(), issue.key(), byAgent.id()));

        for (JsonNode view : views) {
            assertThat(view.has("editedAt")).as("the field editedAt in %s", view).isTrue();
            assertThat(view.path("editedAt").isNull()).as("editedAt is null in %s", view).isTrue();
        }
    }

    @Test
    @DisplayName("S4-AC-07 internal in the body of an edit moves no comment between public and internal, on either app")
    void editDoesNotChangeInternal() {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        Comment answer = fixtures.comment(w.agent(), issue.key(), Unique.text("Public answer"));
        Comment note = fixtures.internalComment(w.agent(), issue.key(), Unique.text("Internal note"));
        Comment question = fixtures.comment(w.reporter(), issue.key(), Unique.text("Question"));
        String nextNote = Unique.text("Still internal");

        JsonNode editedAnswer = TalkFlow.edit(w.agent(), issue.key(), answer.id(),
                body("content", Unique.text("Still public"), "internal", true));
        JsonNode editedNote = TalkFlow.edit(w.agent(), issue.key(), note.id(),
                body("content", nextNote, "internal", false));
        TalkFlow.edit(w.reporter(), issue.key(), question.id(),
                body("content", Unique.text("Still a question"), "internal", true));

        assertThat(editedAnswer.path("internal").asBoolean(true)).as("internal of the public answer").isFalse();
        assertThat(editedNote.path("internal").asBoolean(false)).as("internal of the internal note").isTrue();
        assertThat(TalkFlow.comment(w.admin(), issue.key(), question.id()).path("internal").asBoolean(true))
                .as("internal of the reporter's comment").isFalse();
        ApiResponse portal = TalkFlow.commentPage(w.reporter(), issue.key());
        assertThat(IssueSteps.items(portal)).extracting(c -> c.path("id").asLong(-1))
                .as("comments the reporter sees").containsExactlyInAnyOrder(answer.id(), question.id());
        assertThat(portal.text()).doesNotContain(nextNote);
    }

    // S4-AC-08

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Author.class, names = {"REPORTER_ON_THE_PORTAL", "AGENT_IN_PUBLIC"})
    @DisplayName("S4-AC-08 the author's edit of a deleted comment is 400 COMMENT_DELETED and the comment stays deleted and empty")
    void deletedCommentCannotBeEdited(Author author) {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        Comment written = author.write(fixtures, w, issue.key(), Unique.text("Soon deleted"));
        TalkFlow.delete(author.session(w), issue.key(), written.id());
        JsonNode before = TalkFlow.comment(w.admin(), issue.key(), written.id());

        ApiResponse refused = TalkFlow.editRequest(author.session(w), issue.key(), written.id(),
                body("content", Unique.text("Back again"))).send();

        assertRefused(refused, 400, "COMMENT_DELETED");
        JsonNode after = TalkFlow.comment(w.admin(), issue.key(), written.id());
        assertThat(after).isEqualTo(before);
        assertThat(after.path("deleted").asBoolean(false)).isTrue();
        assertThat(text(after, "content")).isEmpty();
    }

    @Test
    @DisplayName("S4-AC-08, S4-AC-04 the author's edit of a deleted comment on a CLOSED issue is 400 COMMENT_DELETED, not ISSUE_CLOSED")
    void deletedCommentOnClosedIssueCannotBeEdited() {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        Comment written = fixtures.comment(w.agent(), issue.key(), Unique.text("Soon deleted"));
        TalkFlow.delete(w.agent(), issue.key(), written.id());
        flow.close(w.reporter(), issue.key());

        ApiResponse refused = TalkFlow.editRequest(w.agent(), issue.key(), written.id(),
                body("content", Unique.text("Back again"))).send();

        assertRefused(refused, 400, "COMMENT_DELETED");
        assertThat(text(TalkFlow.comment(w.admin(), issue.key(), written.id()), "content")).isEmpty();
    }
}
