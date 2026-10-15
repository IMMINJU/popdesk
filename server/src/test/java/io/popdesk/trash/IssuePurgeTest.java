package io.popdesk.trash;

import static io.popdesk.issue.IssueFlow.NO_KEY;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.label.LabelSteps;
import io.popdesk.link.LinkSteps;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.FailedWrite;
import io.popdesk.support.Fixtures.Attachment;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.File;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import io.popdesk.task.TaskSteps;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Purging an issue from the trash at once ({@code DELETE /api/console/trash/{key}}, TRASH-3, TRASH-4): what goes and
 * what stays (S7A-AC-30), that it is nowhere to be seen (S7A-AC-32), that its files go after the commit and a file
 * that could not be removed is finished by the attachment cleanup (S7A-AC-33), that numbers are not used again
 * (S7A-AC-35), and that no text of it is left in the database (S7A-AC-36).
 *
 * <p>What HTTP cannot show is read from the database: rows by the issue's ID ({@link TrashRows}), file contents in
 * {@code attachment_content} (the {@code db} storage of the tests), and every text column for S7A-AC-36
 * ({@link TalkFlow#tablesContaining}).
 */
class IssuePurgeTest extends BareAppTest {

    /** The attachment cleanup wakes every few seconds of real time; allow a few periods. */
    private static final Duration CLEANUP = Duration.ofSeconds(60);

    private IssueFlow flow;
    private TrashRows rows;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
        rows = new TrashRows(db);
    }

    private long contentCopies(byte[] content) {
        return db.count("select count(*) from attachment_content where data = ?", (Object) content);
    }

    private long attachmentRows(String id) {
        return db.count("select count(*) from attachment where id = ?::uuid", id);
    }

    /** An upload whose step ③ fails, so its row stays {@code pending} with its file written. */
    private Attachment pending(Session session, String key, String name) {
        byte[] content = ("Pending file " + Unique.number()).getBytes(StandardCharsets.UTF_8);
        ApiResponse failed;
        try (FailedWrite storing = FailedWrite.statement(db, "attachment", "update", name)) {
            failed = session.client().post(session.api("/issues/" + key + "/attachments"))
                    .file("file", name, "text/plain", content).send();
        }
        assertThat(failed.status()).as("the upload whose storing failed: " + failed.snippet()).isEqualTo(500);
        List<Map<String, Object>> left = db.rows("select id::text as id, state, issue_id from attachment "
                + "where file_name = ?", name);
        assertThat(left).as("the row of the failed upload " + name).singleElement()
                .satisfies(r -> assertThat(r.get("state")).isEqualTo("pending"))
                .satisfies(r -> assertThat(r.get("issue_id")).isEqualTo(rows.id(key)));
        return new Attachment((String) left.getFirst().get("id"), key, content, null);
    }

    // S7A-AC-30

    @Test
    @DisplayName("S7A-AC-30 purging answers 204 and deletes the issue's comments and mentions, files (pending ones too), task and note, link, label, inbox items and the linked item about it on the other issue, queue rows, and Idempotency-Key row; empties title and content and the values of TITLE and CONTENT entries; keeps the issue row with its key, company, status, and times, and the history entries")
    void purgeEmptiesTheIssueAndKeepsItsRow() {
        TrashScene s = TrashScene.build(fixtures, flow, clock, "IN_PROGRESS");
        String key = s.key();
        UUID id = rows.id(key);
        flow.edit(s.agent(), key, Map.of("title", LinkSteps.title("Edited title"), "content", "Edited content"));
        TalkFlow.mention(s.agent(), key, true, List.of(s.admin().accountId()));
        Attachment pending = pending(s.reporter(), key, Unique.text("pending") + ".txt");
        flow.assign(s.agent(), s.other().key(), s.agent().accountId());
        flow.changeStatus(s.admin(), key, "RESOLVED");
        String idempotencyKey = "key-" + UUID.randomUUID();
        String keyed = s.reporter().client().post("/api/portal/issues").header("Idempotency-Key", idempotencyKey)
                .json(Map.of("companyCode", s.code(), "title", LinkSteps.title("Keyed issue"))).expect(201)
                .jsonText("key");
        UUID keyedId = rows.id(keyed);
        Map<String, Object> issueBefore = rows.issue(key);
        List<Map<String, Object>> historyBefore = rows.history(key);
        Map<String, List<Map<String, Object>>> under = rows.under(key);
        assertThat(under.get("mentions")).as("mentions before the purge").isNotEmpty();
        assertThat(under.get("attachments")).as("files before the purge").hasSize(3);
        assertThat(rows.notifications(key)).as("inbox items about the issue before the purge").isNotEmpty();
        assertThat(linkedItems(id)).as("linked items about the issue on other issues before the purge").isPositive();
        assertThat(db.count("select count(*) from issue_idempotency where issue_id = ?", keyedId))
                .as("Idempotency-Key rows of " + keyed + " before the purge").isEqualTo(1);
        for (String k : List.of(key, keyed)) {
            TrashSteps.delete(s.admin(), k);
        }
        flow.tick();

        ApiResponse answer = TrashSteps.purgeRequest(s.admin(), key).send();
        TrashSteps.purge(s.admin(), keyed);

        assertThat(answer.status()).as("purge: " + answer.snippet()).isEqualTo(204);
        assertThat(answer.body()).as("body of the 204").isEmpty();
        Wait.untilAsserted("the files of " + key + " and their rows are gone", CLEANUP, () -> {
            for (Map<String, Object> file : under.get("attachments")) {
                assertThat(attachmentRows((String) file.get("id"))).as("rows of file " + file).isZero();
            }
            assertThat(contentCopies(pending.content())).as("content of the pending file").isZero();
        });
        Map<String, List<Map<String, Object>>> left = rows.under(key);
        for (Map.Entry<String, List<Map<String, Object>>> kind : left.entrySet()) {
            assertThat(kind.getValue()).as(kind.getKey() + " of the purged issue").isEmpty();
        }
        assertThat(rows.notifications(key)).as("inbox items about the purged issue").isEmpty();
        assertThat(linkedItems(id)).as("linked items about the purged issue on other issues").isZero();
        assertThat(rows.outbox(key)).as("queue rows of the purged issue").isEmpty();
        assertThat(db.count("select count(*) from issue_idempotency where issue_id = ?", keyedId))
                .as("Idempotency-Key rows of the purged " + keyed).isZero();
        assertThat(db.count("select count(*) from label where id = ?", s.label())).as("the label itself").isEqualTo(1);
        assertThat(flow.detail(s.agent(), s.other().key()).path("key").asString(null)).as("the other issue")
                .isEqualTo(s.other().key());

        Map<String, Object> issueAfter = rows.issue(key);
        assertThat(issueAfter.get("title")).as("title").isEqualTo("");
        assertThat(issueAfter.get("content")).as("content").isEqualTo("");
        assertThat(issueAfter.get("search_text")).as("search_text").isEqualTo("");
        for (String column : List.of("id", "company_id", "number", "status", "created_at", "resolved_at",
                "reporter_id")) {
            if (issueBefore.containsKey(column)) {
                assertThat(issueAfter.get(column)).as(column + " of the purged issue")
                        .isEqualTo(issueBefore.get(column));
            }
        }
        List<Map<String, Object>> historyAfter = rows.history(key);
        assertThat(historyAfter.stream().map(r -> r.get("id")).toList()).as("history entry IDs after the purge")
                .containsAll(historyBefore.stream().map(r -> r.get("id")).toList());
        List<Map<String, Object>> texts = historyAfter.stream()
                .filter(r -> List.of("TITLE", "CONTENT").contains(r.get("field"))).toList();
        assertThat(texts.stream().map(r -> r.get("field")).distinct().toList()).as("fields of the text entries")
                .containsExactlyInAnyOrder("TITLE", "CONTENT");
        assertThat(texts).as("TITLE and CONTENT entries").allSatisfy(r -> {
            assertThat(r.get("old_value")).as("old value of " + r).isNull();
            assertThat(r.get("new_value")).as("new value of " + r).isNull();
        });
        Map<String, Object> last = historyAfter.getLast();
        assertThat(last.get("field")).as("field of " + last).isEqualTo(TrashSteps.FIELD);
        assertThat(last.get("old_value")).as("old value of " + last).isEqualTo(TrashSteps.TRASHED);
        assertThat(last.get("new_value")).as("new value of " + last).isEqualTo(TrashSteps.PURGED);
        assertThat(((Number) last.get("actor_id")).longValue()).as("actor of " + last)
                .isEqualTo(s.admin().accountId());
    }

    private long linkedItems(UUID issueId) {
        return db.count("select count(*) from notification where linked_issue_id = ?", issueId);
    }

    // S7A-AC-32

    @Test
    @DisplayName("S7A-AC-32 a purged issue is the 404 of a key nobody has on the console and the portal, also under it, is not in the trash, and the other issue shows no link to it and no LINK history entry about it")
    void purgedIssueIsNowhere() {
        TrashScene s = TrashScene.build(fixtures, flow, clock, "CONFIRMED");
        String key = s.key();
        String other = s.other().key();
        assertThat(linkHistoryNaming(s.agent(), other, key)).as("LINK entries of " + other + " naming " + key)
                .isNotEmpty();
        TrashSteps.delete(s.admin(), key);
        TrashSteps.purge(s.admin(), key);

        Map<String, Function<String, ApiResponse>> reads = new LinkedHashMap<>();
        ApiClient admin = s.admin().client();
        ApiClient customer = s.reporter().client();
        reads.put("console issue", k -> admin.get("/api/console/issues/" + k).send());
        reads.put("console history", k -> admin.get("/api/console/issues/" + k + "/history").send());
        reads.put("console comments", k -> admin.get("/api/console/issues/" + k + "/comments").send());
        reads.put("console file", k -> admin.get("/api/console/issues/" + k + "/attachments/"
                + s.commentFile().id()).send());
        reads.put("console tasks", k -> admin.get(TaskSteps.tasksPath(k)).send());
        reads.put("console links", k -> admin.get(LinkSteps.linksPath(k)).send());
        reads.put("portal issue", k -> customer.get("/api/portal/issues/" + k).send());
        reads.put("portal comments", k -> customer.get("/api/portal/issues/" + k + "/comments").send());
        reads.put("portal file", k -> customer.get("/api/portal/issues/" + k + "/attachments/"
                + s.issueFile().id()).send());
        reads.put("delete again", k -> TrashSteps.deleteRequest(s.admin(), k).send());
        for (Map.Entry<String, Function<String, ApiResponse>> read : reads.entrySet()) {
            ApiResponse answer = read.getValue().apply(key);
            ApiResponse missing = read.getValue().apply(NO_KEY);
            TrashSteps.assertLikeMissing(answer, missing);
        }
        assertThat(TrashSteps.entry(s.admin(), key)).as("trash entry of the purged issue").isNull();
        assertThat(LinkSteps.linkedKeys(s.agent(), other)).as("issues linked to " + other).doesNotContain(key);
        assertThat(LinkSteps.linkedKeys(s.admin(), other)).as("issues linked to " + other + " for the admin")
                .doesNotContain(key);
        assertThat(linkHistoryNaming(s.admin(), other, key)).as("LINK entries of " + other + " naming " + key
                + " for the admin").isEmpty();
    }

    private static List<JsonNode> linkHistoryNaming(Session staff, String key, String named) {
        return LinkSteps.linkEntries(staff, key).stream().filter(e -> e.toString().contains("\"" + named + "\""))
                .toList();
    }

    // S7A-AC-33

    @Test
    @DisplayName("S7A-AC-33 purging removes the files of the issue from the storage, the stored and the pending ones, and leaves no attachment row")
    void purgeRemovesTheFiles() {
        TrashScene s = TrashScene.build(fixtures, flow, clock, "CONFIRMED");
        String key = s.key();
        Attachment pending = pending(s.reporter(), key, Unique.text("pending") + ".txt");
        List<Attachment> files = List.of(s.issueFile(), s.commentFile(), pending);
        for (Attachment file : files) {
            assertThat(contentCopies(file.content())).as("content of file " + file.id() + " before").isEqualTo(1);
        }
        TrashSteps.delete(s.admin(), key);

        TrashSteps.purge(s.admin(), key);

        Wait.untilAsserted("the files of the purged issue are gone", CLEANUP, () -> {
            for (Attachment file : files) {
                assertThat(contentCopies(file.content())).as("content of file " + file.id()).isZero();
                assertThat(attachmentRows(file.id())).as("rows of file " + file.id()).isZero();
            }
        });
    }

    @Test
    @DisplayName("S7A-AC-33 a file the purge could not remove stays purging without its name and comment, and the attachment cleanup removes it and its row later")
    void cleanupFinishesAFileThePurgeCouldNotRemove() {
        TrashScene s = TrashScene.build(fixtures, flow, clock, "CONFIRMED");
        String key = s.key();
        Attachment stuck = s.commentFile();
        TrashSteps.delete(s.admin(), key);

        try (FailedWrite removing = FailedWrite.statement(db, "attachment_content", "update or delete",
                "\\x" + HexFormat.of().formatHex(stuck.content()))) {
            TrashSteps.purge(s.admin(), key);
            Wait.untilAsserted("the other file of the purged issue is gone", CLEANUP, () -> {
                assertThat(contentCopies(s.issueFile().content())).as("content of the other file").isZero();
                assertThat(attachmentRows(s.issueFile().id())).as("rows of the other file").isZero();
            });
            Map<String, Object> row = db.row("select state, file_name, comment_id from attachment where id = ?::uuid",
                    stuck.id());
            assertThat(row.get("state")).as("state of the file that could not be removed").isEqualTo("purging");
            assertThat(row.get("file_name")).as("file name of the file that could not be removed").isNull();
            assertThat(row.get("comment_id")).as("comment of the file that could not be removed").isNull();
            assertThat(contentCopies(stuck.content())).as("content of the file that could not be removed")
                    .isEqualTo(1);
        }

        clock.advance(Duration.ofHours(25));

        Wait.untilAsserted("the attachment cleanup removed the file and its row", CLEANUP, () -> {
            assertThat(contentCopies(stuck.content())).as("content of the file").isZero();
            assertThat(attachmentRows(stuck.id())).as("rows of the file").isZero();
        });
    }

    // S7A-AC-35

    @Test
    @DisplayName("S7A-AC-35 the numbers of a deleted and of a purged issue are not used again: the next issue gets the next number")
    void numbersAreNotUsedAgain() {
        Session admin = fixtures.admin(flow.admin());
        Company company = fixtures.createCompany(admin);
        Session reporter = fixtures.customer(admin, company.code());
        String code = company.code();
        fixtures.fileIssue(reporter, code);
        Issue second = fixtures.fileIssue(reporter, code);
        TrashSteps.delete(admin, second.key());

        Issue third = fixtures.fileIssue(reporter, code);
        TrashSteps.delete(admin, third.key());
        TrashSteps.purge(admin, third.key());
        TrashSteps.purge(admin, second.key());
        Issue fourth = fixtures.fileIssue(reporter, code);

        assertThat(List.of(second.key(), third.key(), fourth.key())).as("keys of the second, third, and fourth issue")
                .containsExactly(code + "-2", code + "-3", code + "-4");
    }

    // S7A-AC-36

    @Test
    @DisplayName("S7A-AC-36 after a purge no text column of the database outside the audit log holds the marker the test put in the issue's title, content, comments, task, note, and file names, edits included")
    void purgeLeavesNoTextAnywhere() {
        String marker = IssueFlow.word();
        Session admin = fixtures.admin(flow.admin());
        Company company = fixtures.createCompany(admin);
        String code = company.code();
        Session reporter = fixtures.customer(admin, code);
        Session agent = fixtures.agent(admin, code);
        Issue other = fixtures.fileIssue(reporter, code);
        Issue issue = fixtures.fileIssue(reporter, NewIssue.in(code).title("Title " + marker + " first")
                .content("Content " + marker + " first").file(File.text(marker + "-filed.txt", "Filed " + marker)));
        String key = issue.key();
        flow.edit(agent, key, Map.of("title", "Title " + marker + " second", "content", "Content " + marker
                + " second"));
        long customerComment = fixtures.comment(reporter, key, "Customer " + marker).id();
        TalkFlow.editContent(reporter, key, customerComment, "Customer " + marker + " edited");
        long staffComment = fixtures.comment(agent, key, "Staff " + marker).id();
        fixtures.attachToComment(agent, key, staffComment, File.text(marker + "-answer.txt", "Answer " + marker));
        fixtures.internalComment(agent, key, "Internal " + marker);
        fixtures.attach(reporter, key, File.text(marker + "-later.txt", "Later " + marker));
        pending(reporter, key, marker + "-pending.txt");
        long task = TaskSteps.id(TaskSteps.create(agent, key, "Task " + marker));
        long note = TaskSteps.id(TaskSteps.note(agent, key, task, "Note " + marker));
        TaskSteps.editNote(agent, key, task, note, "Note " + marker + " edited");
        LabelSteps.add(agent, key, LabelSteps.id(LabelSteps.create(admin, code)));
        LinkSteps.link(agent, key, other.key(), LinkSteps.RELATED);
        flow.moveTo(agent, key, "RESOLVED");
        assertThat(holding(marker)).as("columns holding the marker before the purge").contains("issue.title",
                "issue.content", "comment.content", "task.title", "task_note.content", "attachment.file_name",
                "issue_history.old_value", "issue_history.new_value");
        TrashSteps.delete(admin, key);

        TrashSteps.purge(admin, key);

        Wait.untilAsserted("no column outside the audit log holds the marker " + marker, CLEANUP,
                () -> assertThat(holding(marker)).as("columns holding the marker after the purge").isEmpty());
    }

    /** Columns outside the audit log ({@code audit_log}, {@code audit_chain}) that hold the text, ignoring case. */
    private List<String> holding(String text) {
        return TalkFlow.tablesContaining(db, text).stream().filter(c -> !c.startsWith("audit_")).toList();
    }
}
