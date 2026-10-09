package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.bytes;
import static io.popdesk.attachment.AttachmentSteps.fieldCode;
import static io.popdesk.attachment.AttachmentSteps.list;
import static io.popdesk.attachment.AttachmentSteps.text;
import static io.popdesk.attachment.AttachmentSteps.upload;
import static io.popdesk.attachment.AttachmentSteps.uploadToComment;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.AttachmentRows.Row;
import io.popdesk.attachment.AttachmentSteps.Scene;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.File;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Who may attach, where an upload ends up (the row's state and storage kind, the content in
 * {@code attachment_content}), and attachment IDs. The order of the three steps is in
 * {@link AttachmentStatementLogTest}; the JPA rules in {@link AttachmentArchitectureTest}.
 */
class AttachmentStorageTest extends BareAppTest {

    private static final Pattern UUID_TEXT =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private AttachmentSteps steps;
    private AttachmentRows rows;

    @BeforeEach
    void setUp() {
        steps = new AttachmentSteps(fixtures);
        rows = new AttachmentRows(db);
    }

    @Test
    @DisplayName("S0-AC-56 an upload ends stored in the db storage, with its content in attachment_content and not in the metadata table")
    void uploadEndsStored() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        byte[] content = bytes(200_000, Unique.number());
        String name = Unique.text("stored") + ".bin";

        ApiResponse created = upload(s.customer(), issue.key(), name, content).expectStatus(201);

        String id = created.jsonText("id");
        Row row = rows.byId(id);
        assertThat(row.state()).isEqualTo("stored");
        assertThat(row.holds("db")).as("storage kind db in %s", row).isTrue();
        assertThat(rows.byteaColumns(row.table())).as("bytea columns of the metadata table").isEmpty();
        assertThat(rows.contentCopies(content)).as("rows of attachment_content with the content").isEqualTo(1);
        assertThat(created.json().path("size").asLong()).isEqualTo(content.length);
        assertThat(text(created.json(), "fileName")).isEqualTo(name);
        assertThat(AttachmentSteps.download(s.customer(), issue.key(), id).expectStatus(200).body())
                .isEqualTo(content);
    }

    @Test
    @DisplayName("S0-AC-56 files sent with a new issue are stored on that issue")
    void filesWithNewIssueAreStored() {
        Scene s = steps.scene();
        String first = Unique.text("with-issue-a") + ".txt";
        String second = Unique.text("with-issue-b") + ".txt";

        Issue issue = fixtures.fileIssue(s.customer(), NewIssue.in(s.code())
                .file(File.text(first, "first file"))
                .file(File.text(second, "second file")));

        List<JsonNode> items = list(s.customer(), issue.key());
        assertThat(items).extracting(n -> text(n, "fileName")).containsExactlyInAnyOrder(first, second);
        assertThat(items).allSatisfy(n -> assertThat(n.path("commentId").isNull()).isTrue());
        for (JsonNode item : items) {
            assertThat(rows.byId(text(item, "id")).state()).isEqualTo("stored");
        }
    }

    @Test
    @DisplayName("S0-AC-56 attachment IDs are lower-case UUIDs, all different and far apart, not a sequence")
    void idsAreHardToGuess() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(upload(s.customer(), issue.key(), "id-" + i + ".txt", bytes(10, i)).expectStatus(201)
                    .jsonText("id"));
        }

        assertThat(ids).allMatch(id -> UUID_TEXT.matcher(id).matches()).doesNotHaveDuplicates();
        BigInteger near = BigInteger.ONE.shiftLeft(32);
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                BigInteger distance = number(ids.get(i)).subtract(number(ids.get(j))).abs();
                assertThat(distance).as("distance between %s and %s", ids.get(i), ids.get(j))
                        .isGreaterThan(near);
            }
        }
    }

    @Test
    @DisplayName("S0-AC-56 the reporter and staff attach to their own comments; the attachment points at the comment")
    void authorsAttachToOwnComments() {
        Scene s = steps.scene();
        Session agent = fixtures.agent(s.admin(), s.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        Comment byCustomer = fixtures.comment(s.customer(), issue.key(), "Customer comment");
        Comment internal = fixtures.internalComment(agent, issue.key(), "Internal note");

        String onCustomer = uploadToComment(s.customer(), issue.key(), byCustomer.id(), "customer.txt",
                bytes(20, 1)).expectStatus(201).jsonText("id");
        String onInternal = uploadToComment(agent, issue.key(), internal.id(), "internal.txt", bytes(20, 2))
                .expectStatus(201).jsonText("id");

        List<JsonNode> seenByStaff = list(agent, issue.key());
        assertThat(entry(seenByStaff, onCustomer).path("commentId").asLong()).isEqualTo(byCustomer.id());
        assertThat(entry(seenByStaff, onCustomer).path("internal").booleanValue()).isFalse();
        assertThat(entry(seenByStaff, onInternal).path("commentId").asLong()).isEqualTo(internal.id());
        assertThat(entry(seenByStaff, onInternal).path("internal").booleanValue()).isTrue();
        assertThat(rows.byId(onCustomer).state()).isEqualTo("stored");
        assertThat(rows.byId(onInternal).state()).isEqualTo("stored");
    }

    @Test
    @DisplayName("S0-AC-56 attaching to a comment of someone else is 400 with commentId UNKNOWN_VALUE and stores nothing")
    void otherPeoplesCommentIsRejected() {
        Scene s = steps.scene();
        Session agent = fixtures.agent(s.admin(), s.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        Comment byAgent = fixtures.comment(agent, issue.key(), "Agent answer");
        String name = Unique.text("not-mine") + ".txt";

        ApiResponse rejected = uploadToComment(s.customer(), issue.key(), byAgent.id(), name, bytes(20, 3));

        assertThat(rejected.status()).isEqualTo(400);
        assertThat(rejected.errorCode()).isEqualTo("INVALID_INPUT");
        assertThat(fieldCode(rejected, "commentId")).isEqualTo("UNKNOWN_VALUE");
        assertNothingStored(agent, issue.key(), name);
    }

    @Test
    @DisplayName("S0-AC-56 a colleague who can see the issue but may not comment on it gets 403 and stores nothing")
    void colleagueMayNotAttach() {
        Scene s = steps.scene();
        Session colleague = fixtures.customer(s.admin(), s.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        String name = Unique.text("colleague") + ".txt";

        ApiResponse rejected = upload(colleague, issue.key(), name, bytes(20, 4));

        assertThat(rejected.status()).isEqualTo(403);
        assertThat(rejected.errorCode()).isEqualTo("FORBIDDEN");
        assertNothingStored(s.admin(), issue.key(), name);
    }

    @Test
    @DisplayName("S0-AC-56 an agent not assigned to the issue's company gets 404 and stores nothing")
    void unassignedAgentGets404() {
        Scene s = steps.scene();
        Company other = fixtures.createCompany(s.admin());
        Session outsider = fixtures.agent(s.admin(), other.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        String name = Unique.text("outsider") + ".txt";

        ApiResponse rejected = upload(outsider, issue.key(), name, bytes(20, 5));

        assertThat(rejected.status()).isEqualTo(404);
        assertThat(rejected.errorCode()).isEqualTo("NOT_FOUND");
        assertNothingStored(s.admin(), issue.key(), name);
    }

    private void assertNothingStored(Session staff, String key, String fileName) {
        assertThat(list(staff, key)).extracting(n -> text(n, "fileName")).doesNotContain(fileName);
        assertThat(rows.byFileName(fileName)).extracting(Row::state).doesNotContain("stored");
    }

    private static JsonNode entry(List<JsonNode> items, String id) {
        return items.stream().filter(n -> id.equals(text(n, "id"))).findFirst()
                .orElseThrow(() -> new AssertionError("No attachment " + id + " in " + items));
    }

    private static BigInteger number(String uuid) {
        return new BigInteger(uuid.replace("-", ""), 16);
    }
}
