package io.popdesk.issue;

import static io.popdesk.issue.IssueSteps.items;
import static io.popdesk.issue.IssueSteps.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Attachment;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.File;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * Deleting a comment: who may, the trace it leaves in the list, deleting again, a closed issue, the
 * comment's attachments, what the portal says about who deleted, the first response time, and that
 * the content is gone from every answer and from the database.
 */
class CommentDeleteTest extends BareAppTest {

    /** Fields of a page or of a comment that hold a number which is no account ID. */
    private static final Set<String> NOT_ACCOUNTS = Set.of("id", "page", "size", "totalElements", "totalPages");

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    /** Who deletes which comment. */
    enum Deleter {
        REPORTER_THEIR_OWN,
        AGENT_THEIR_OWN,
        ADMIN_AN_AGENTS,
        ADMIN_THE_REPORTERS;

        Session session(World w) {
            return switch (this) {
                case REPORTER_THEIR_OWN -> w.reporter();
                case AGENT_THEIR_OWN -> w.agent();
                default -> w.admin();
            };
        }

        Session author(World w) {
            return this == REPORTER_THEIR_OWN || this == ADMIN_THE_REPORTERS ? w.reporter() : w.agent();
        }
    }

    private static List<JsonNode> attachments(Session session, String key) {
        return items(session.client().get(session.api("/issues/" + key + "/attachments")).query("size", 100)
                .expect(200));
    }

    private static JsonNode attachment(Session session, String key, String id) {
        return attachments(session, key).stream().filter(a -> id.equals(text(a, "id"))).findFirst()
                .orElseThrow(() -> new AssertionError("Attachment " + id + " is not in the list of " + key));
    }

    private static ApiResponse download(Session session, String key, String id) {
        return session.client().get(session.api("/issues/" + key + "/attachments/" + id)).send();
    }

    // S4-AC-10

    @ParameterizedTest(name = "{0}")
    @EnumSource(Deleter.class)
    @DisplayName("S4-AC-10 the author or an admin deletes a comment: 204, and the list keeps it in its place as a deleted comment with an empty content, the time, and for staff who deleted it; the count stays")
    void authorOrAdminDeletes(Deleter deleter) {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        Comment first = fixtures.comment(w.reporter(), issue.key(), Unique.text("First"));
        flow.tick();
        Comment target = fixtures.comment(deleter.author(w), issue.key(), Unique.text("To delete"));
        flow.tick();
        Comment last = fixtures.comment(w.agent(), issue.key(), Unique.text("Last"));
        Instant deletedAt = clock.advance(Duration.ofMinutes(5));

        ApiResponse deleted = TalkFlow.deleteRequest(deleter.session(w), issue.key(), target.id()).send();

        assertThat(deleted.status()).as(deleted.request() + ": " + deleted.snippet()).isEqualTo(204);
        assertThat(deleted.body()).as("body of the 204").isEmpty();
        for (Session reader : List.of(w.agent(), w.reporter())) {
            ApiResponse page = TalkFlow.commentPage(reader, issue.key());
            assertThat(items(page)).extracting(c -> c.path("id").asLong(-1)).as("comments for %s", reader.role())
                    .containsExactly(first.id(), target.id(), last.id());
            assertThat(IssueFlow.total(page)).as("totalElements for %s", reader.role()).isEqualTo(3);
            JsonNode trace = items(page).get(1);
            assertThat(trace.path("deleted").asBoolean(false)).as("deleted for %s", reader.role()).isTrue();
            assertThat(text(trace, "content")).as("content for %s", reader.role()).isEmpty();
            assertThat(IssueFlow.time(trace, "deletedAt")).as("deletedAt for %s", reader.role()).isEqualTo(deletedAt);
            assertThat(items(page).get(0).path("deleted").asBoolean(true)).as("deleted of the first comment")
                    .isFalse();
            assertThat(text(items(page).get(2), "content")).isEqualTo(text(last.json(), "content"));
        }
        assertThat(TalkFlow.comment(w.agent(), issue.key(), target.id()).path("deletedBy").path("accountId")
                .asLong(-1)).as("deletedBy on the console").isEqualTo(deleter.session(w).accountId());
    }

    // S4-AC-11

    /** Someone who sees a comment they may not delete. */
    enum Intruder {
        AGENT_ON_ANOTHER_AGENTS_COMMENT,
        AGENT_ON_THE_REPORTERS_COMMENT,
        REPORTER_ON_A_STAFF_COMMENT
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Intruder.class)
    @DisplayName("S4-AC-11 an agent deleting someone else's comment and the reporter deleting a staff comment get 403 FORBIDDEN, and the comment stays")
    void othersMayNotDelete(Intruder intruder) {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        Session caller;
        Session author;
        switch (intruder) {
            case AGENT_ON_ANOTHER_AGENTS_COMMENT -> {
                caller = fixtures.agent(w.admin(), w.code());
                author = w.agent();
            }
            case AGENT_ON_THE_REPORTERS_COMMENT -> {
                caller = w.agent();
                author = w.reporter();
            }
            default -> {
                caller = w.reporter();
                author = w.agent();
            }
        }
        Comment target = fixtures.comment(author, issue.key(), Unique.text("Stays"));
        // The author can delete a comment of theirs, so the 403 below is about the caller.
        TalkFlow.delete(author, issue.key(), fixtures.comment(author, issue.key(), Unique.text("Goes")).id());
        JsonNode before = TalkFlow.comment(w.admin(), issue.key(), target.id());

        ApiResponse refused = TalkFlow.deleteRequest(caller, issue.key(), target.id()).send();

        assertThat(refused.status()).as(refused.request() + ": " + refused.snippet()).isEqualTo(403);
        assertThat(refused.errorCode()).isEqualTo("FORBIDDEN");
        assertThat(TalkFlow.comment(w.admin(), issue.key(), target.id())).as("the comment after the refused deletion")
                .isEqualTo(before);
        assertThat(before.path("deleted").asBoolean(true)).isFalse();
    }

    @Test
    @DisplayName("S4-AC-11 a colleague of the same company who sees the reporter's comment on a public issue gets 403 FORBIDDEN on deleting it, and the comment stays")
    void colleagueMayNotDeleteTheReportersComment() {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        Comment target = fixtures.comment(w.reporter(), issue.key(), Unique.text("Stays"));
        // The reporter can delete a comment of theirs, so the 403 below is about the caller.
        TalkFlow.delete(w.reporter(), issue.key(),
                fixtures.comment(w.reporter(), issue.key(), Unique.text("Goes")).id());
        assertThat(TalkFlow.find(w.colleague(), issue.key(), target.id())).as("the comment as the colleague sees it")
                .isNotNull();
        JsonNode before = TalkFlow.comment(w.admin(), issue.key(), target.id());

        ApiResponse refused = TalkFlow.deleteRequest(w.colleague(), issue.key(), target.id()).send();

        assertThat(refused.status()).as(refused.request() + ": " + refused.snippet()).isEqualTo(403);
        assertThat(refused.errorCode()).isEqualTo("FORBIDDEN");
        assertThat(TalkFlow.comment(w.admin(), issue.key(), target.id())).as("the comment after the refused deletion")
                .isEqualTo(before);
        assertThat(before.path("deleted").asBoolean(true)).isFalse();
    }

    // S4-AC-12

    @Test
    @DisplayName("S4-AC-12 deleting a deleted comment again, by its author and by an admin, is 204 and keeps the first time and the first person")
    void deletingAgainChangesNothing() {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        Comment target = fixtures.comment(w.agent(), issue.key(), Unique.text("Deleted twice"));
        Instant deletedAt = clock.advance(Duration.ofMinutes(5));
        TalkFlow.delete(w.agent(), issue.key(), target.id());
        JsonNode onConsole = TalkFlow.comment(w.admin(), issue.key(), target.id());
        JsonNode onPortal = TalkFlow.comment(w.reporter(), issue.key(), target.id());
        clock.advance(Duration.ofMinutes(5));

        ApiResponse byAuthor = TalkFlow.deleteRequest(w.agent(), issue.key(), target.id()).send();
        clock.advance(Duration.ofMinutes(5));
        ApiResponse byAdmin = TalkFlow.deleteRequest(w.admin(), issue.key(), target.id()).send();

        assertThat(byAuthor.status()).as("the author deletes again").isEqualTo(204);
        assertThat(byAdmin.status()).as("an admin deletes again").isEqualTo(204);
        JsonNode after = TalkFlow.comment(w.admin(), issue.key(), target.id());
        assertThat(IssueFlow.time(after, "deletedAt")).isEqualTo(deletedAt);
        assertThat(after.path("deletedBy").path("accountId").asLong(-1)).as("deletedBy stays the author")
                .isEqualTo(w.agent().accountId());
        assertThat(after).as("the comment on the console").isEqualTo(onConsole);
        assertThat(TalkFlow.comment(w.reporter(), issue.key(), target.id())).as("the comment on the portal")
                .isEqualTo(onPortal);
    }

    // S4-AC-13

    @ParameterizedTest(name = "{0}")
    @EnumSource(Deleter.class)
    @DisplayName("S4-AC-13 a comment on a CLOSED issue is deleted by its author or an admin: 204, the trace is in the list, and the issue stays CLOSED")
    void commentOnClosedIssueIsDeleted(Deleter deleter) {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        Comment target = fixtures.comment(deleter.author(w), issue.key(), Unique.text("Before closing"));
        flow.close(w.reporter(), issue.key());

        ApiResponse deleted = TalkFlow.deleteRequest(deleter.session(w), issue.key(), target.id()).send();

        assertThat(deleted.status()).as(deleted.request() + ": " + deleted.snippet()).isEqualTo(204);
        JsonNode trace = TalkFlow.comment(w.admin(), issue.key(), target.id());
        assertThat(trace.path("deleted").asBoolean(false)).isTrue();
        assertThat(text(trace, "content")).isEmpty();
        assertThat(text(flow.detail(w.admin(), issue.key()), "status")).isEqualTo("CLOSED");
    }

    // S4-AC-14

    @Test
    @DisplayName("S4-AC-14 deleting a comment deletes its attachment as deleting the attachment does, and leaves the issue's attachment and another comment's attachment to download")
    void attachmentsOfTheCommentAreDeletedWithIt() {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        String key = issue.key();
        Comment target = fixtures.comment(w.reporter(), key, Unique.text("With a file"));
        Comment other = fixtures.comment(w.reporter(), key, Unique.text("With another file"));
        Attachment onTarget = fixtures.attachToComment(w.reporter(), key, target.id(),
                File.text("on-the-comment.txt", Unique.text("On the comment")));
        Attachment onOther = fixtures.attachToComment(w.reporter(), key, other.id(),
                File.text("on-another-comment.txt", Unique.text("On another comment")));
        Attachment onIssue = fixtures.attach(w.reporter(), key,
                File.text("on-the-issue.txt", Unique.text("On the issue")));
        Attachment deletedAlone = fixtures.attach(w.reporter(), key,
                File.text("deleted-alone.txt", Unique.text("Deleted alone")));
        w.reporter().client().delete(w.reporter().api("/issues/" + key + "/attachments/" + deletedAlone.id()))
                .expect(204);

        TalkFlow.delete(w.reporter(), key, target.id());

        for (Session reader : List.of(w.reporter(), w.agent())) {
            JsonNode gone = attachment(reader, key, onTarget.id());
            JsonNode reference = attachment(reader, key, deletedAlone.id());
            assertThat(reference.path("deleted").asBoolean(false)).as("the attachment deleted alone").isTrue();
            assertThat(gone.path("deleted").asBoolean(false)).as("deleted of the comment's attachment for %s",
                    reader.role()).isTrue();
            assertThat(gone.path("fileName")).as("fileName, like the attachment deleted alone")
                    .isEqualTo(reference.path("fileName"));
            assertThat(gone.path("deletedAt").isString()).as("deletedAt of the comment's attachment: %s", gone)
                    .isTrue();
            ApiResponse goneDownload = download(reader, key, onTarget.id());
            ApiResponse referenceDownload = download(reader, key, deletedAlone.id());
            assertThat(goneDownload.status()).as("download of the comment's attachment for %s", reader.role())
                    .isEqualTo(referenceDownload.status()).isNotEqualTo(200);
            assertThat(goneDownload.text()).isEqualTo(referenceDownload.text());
            for (Attachment kept : List.of(onOther, onIssue)) {
                assertThat(attachment(reader, key, kept.id()).path("deleted").asBoolean(true))
                        .as("deleted of %s", kept.id()).isFalse();
                ApiResponse keptDownload = download(reader, key, kept.id());
                assertThat(keptDownload.status()).as("download of %s", kept.id()).isEqualTo(200);
                assertThat(keptDownload.body()).isEqualTo(kept.content());
            }
        }
    }

    // S4-AC-15

    @Test
    @DisplayName("S4-AC-15 after an admin deletes an agent's public comment, the portal's comment list holds no name, email, or account ID of either and no deletedBy, and still says the support team wrote it; the console names the admin as deletedBy")
    void portalDoesNotSayWhoDeleted() {
        World w = flow.world();
        Session admin = fixtures.admin(w.admin());
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        fixtures.comment(w.reporter(), issue.key(), Unique.text("Question"));
        Comment answer = fixtures.comment(w.agent(), issue.key(), Unique.text("Answer"));
        Instant deletedAt = clock.advance(Duration.ofMinutes(5));

        TalkFlow.delete(admin, issue.key(), answer.id());

        ApiResponse portal = TalkFlow.commentPage(w.reporter(), issue.key());
        JsonNode trace = items(portal).stream().filter(c -> c.path("id").asLong(-1) == answer.id()).findFirst()
                .orElseThrow(() -> new AssertionError("The deleted comment is not in " + portal.snippet()));
        assertThat(trace.path("deleted").asBoolean(false)).isTrue();
        assertThat(IssueFlow.time(trace, "deletedAt")).isEqualTo(deletedAt);
        assertThat(trace.has("deletedBy")).as("the field deletedBy on the portal: %s", trace).isFalse();
        assertThat(text(trace.path("author"), "kind")).isEqualTo("SUPPORT_TEAM");
        assertThat(trace.path("author").path("name").isNull()).as("author name is null").isTrue();
        assertThat(trace.path("author").path("accountId").isNull()).as("author accountId is null").isTrue();
        assertThat(portal.text()).as("the reporter's name, where the portal names customers")
                .contains(w.reporter().name());
        for (Session staff : List.of(admin, w.agent())) {
            assertThat(portal.text()).as("name of %s", staff.role()).doesNotContainIgnoringCase(staff.name());
            assertThat(portal.text()).as("email of %s", staff.role()).doesNotContainIgnoringCase(staff.email());
            assertThat(TalkFlow.placesOfNumber(portal.json(), staff.accountId(), NOT_ACCOUNTS))
                    .as("account ID %s of %s", staff.accountId(), staff.role()).isEmpty();
        }
        JsonNode deletedBy = TalkFlow.comment(w.agent(), issue.key(), answer.id()).path("deletedBy");
        assertThat(deletedBy.path("accountId").asLong(-1)).isEqualTo(admin.accountId());
        assertThat(text(deletedBy, "name")).isEqualTo(admin.name());
        assertThat(text(deletedBy, "role")).isEqualTo("ADMIN");
    }

    // S4-AC-17

    @Test
    @DisplayName("S4-AC-17 the first response time of the issue stays when the first public staff comment is deleted")
    void firstResponseTimeStays() {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        clock.advance(Duration.ofMinutes(10));
        Comment answer = fixtures.comment(w.agent(), issue.key(), Unique.text("First answer"));
        Instant firstResponseAt = IssueFlow.time(flow.detail(w.agent(), issue.key()), "firstResponseAt");
        assertThat(firstResponseAt).as("firstResponseAt after the first answer").isNotNull();
        clock.advance(Duration.ofMinutes(10));

        TalkFlow.delete(w.agent(), issue.key(), answer.id());

        assertThat(IssueFlow.time(flow.detail(w.agent(), issue.key()), "firstResponseAt"))
                .as("firstResponseAt after the answer is deleted").isEqualTo(firstResponseAt);
    }

    // S4-AC-18

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Deleter.class, names = {"REPORTER_THEIR_OWN", "ADMIN_AN_AGENTS"})
    @DisplayName("S4-AC-18 a word of a deleted comment is in no comment list of either app and in no row of any table")
    void deletedContentIsNowhere(Deleter deleter) {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        String word = IssueFlow.word();
        Comment target = fixtures.comment(deleter.author(w), issue.key(), "Call me on " + word + " please");
        assertThat(TalkFlow.commentPage(w.reporter(), issue.key()).text()).as("the portal list before").contains(word);
        assertThat(TalkFlow.tablesContaining(db, word)).as("columns holding the word before").isNotEmpty();

        TalkFlow.delete(deleter.session(w), issue.key(), target.id());

        assertThat(TalkFlow.commentPage(w.reporter(), issue.key()).text()).as("the portal list").doesNotContain(word);
        assertThat(TalkFlow.commentPage(w.agent(), issue.key()).text()).as("the console list").doesNotContain(word);
        assertThat(TalkFlow.tablesContaining(db, word)).as("columns still holding the word").isEmpty();
    }

    @Test
    @DisplayName("S4-AC-18 the content a comment had before an edit is in no row of any table after the edit")
    void contentBeforeAnEditIsNotKept() {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        String old = IssueFlow.word();
        String next = IssueFlow.word();
        Comment target = fixtures.comment(w.reporter(), issue.key(), "Call me on " + old);
        assertThat(TalkFlow.tablesContaining(db, old)).as("columns holding the first content").isNotEmpty();

        TalkFlow.editContent(w.reporter(), issue.key(), target.id(), "Write to " + next);

        assertThat(TalkFlow.tablesContaining(db, next)).as("columns holding the new content").isNotEmpty();
        assertThat(TalkFlow.tablesContaining(db, old)).as("columns still holding the first content").isEmpty();
    }
}
