package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.bytes;
import static io.popdesk.attachment.AttachmentSteps.download;
import static io.popdesk.attachment.AttachmentSteps.entry;
import static io.popdesk.attachment.AttachmentSteps.path;
import static io.popdesk.attachment.AttachmentSteps.text;
import static io.popdesk.attachment.AttachmentSteps.upload;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.AttachmentSteps.Scene;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Deleting an attachment: who may, the trace left in the list, and the row and content in the database. */
class AttachmentDeleteTest extends BareAppTest {

    private AttachmentSteps steps;
    private AttachmentRows rows;

    @BeforeEach
    void setUp() {
        steps = new AttachmentSteps(fixtures);
        rows = new AttachmentRows(db);
    }

    @Test
    @DisplayName("S0-AC-61 the uploader deletes: 204, the row ends purged, the content is gone, and the list keeps a trace without the file name")
    void uploaderDeletes() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        byte[] content = bytes(5_000, Unique.number());
        String id = upload(s.customer(), issue.key(), Unique.text("delete-me") + ".bin", content).expectStatus(201)
                .jsonText("id");
        Instant deletedAt = clock.advance(Duration.ofMinutes(5));

        ApiResponse deleted = s.customer().client().delete(path(s.customer(), issue.key(), id)).send();

        assertThat(deleted.status()).isEqualTo(204);
        JsonNode trace = entry(s.customer(), issue.key(), id);
        assertThat(trace).as("trace of %s in the list", id).isNotNull();
        assertThat(trace.path("deleted").booleanValue()).isTrue();
        assertThat(trace.path("fileName").isNull()).as("fileName is null").isTrue();
        assertThat(Instant.parse(text(trace, "deletedAt"))).isEqualTo(deletedAt);
        assertThat(trace.path("deletedBy").path("accountId").asLong()).isEqualTo(s.customer().accountId());
        assertThat(rows.byId(id).state()).isEqualTo("purged");
        assertThat(rows.contentCopies(content)).as("content rows left").isZero();
        assertThat(download(s.customer(), issue.key(), id).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("S0-AC-61 an admin deletes someone else's attachment and is recorded as the one who deleted it")
    void adminDeletes() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        String id = upload(s.customer(), issue.key(), "by-customer.bin", bytes(100, 1)).expectStatus(201)
                .jsonText("id");

        ApiResponse deleted = s.admin().client().delete(path(s.admin(), issue.key(), id)).send();

        assertThat(deleted.status()).isEqualTo(204);
        JsonNode trace = entry(s.admin(), issue.key(), id);
        assertThat(trace).isNotNull();
        assertThat(trace.path("deleted").booleanValue()).isTrue();
        assertThat(trace.path("fileName").isNull()).isTrue();
        assertThat(trace.path("deletedBy").path("accountId").asLong()).isEqualTo(s.admin().accountId());
        assertThat(rows.byId(id).state()).isEqualTo("purged");
    }

    @Test
    @DisplayName("S0-AC-61 a colleague who can see the issue gets 403 FORBIDDEN and the attachment stays")
    void colleagueMayNotDelete() {
        Scene s = steps.scene();
        Session colleague = fixtures.customer(s.admin(), s.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        byte[] content = bytes(100, 2);
        String id = upload(s.customer(), issue.key(), "keep.bin", content).expectStatus(201).jsonText("id");

        ApiResponse rejected = colleague.client().delete(path(colleague, issue.key(), id)).send();

        assertThat(rejected.status()).isEqualTo(403);
        assertThat(rejected.errorCode()).isEqualTo("FORBIDDEN");
        assertStays(s.customer(), issue.key(), id, content);
    }

    @Test
    @DisplayName("S0-AC-61 an assigned agent who did not upload gets 403 FORBIDDEN and the attachment stays")
    void otherAgentMayNotDelete() {
        Scene s = steps.scene();
        Session agent = fixtures.agent(s.admin(), s.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        byte[] content = bytes(100, 3);
        String id = upload(s.customer(), issue.key(), "keep.bin", content).expectStatus(201).jsonText("id");

        ApiResponse rejected = agent.client().delete(path(agent, issue.key(), id)).send();

        assertThat(rejected.status()).isEqualTo(403);
        assertThat(rejected.errorCode()).isEqualTo("FORBIDDEN");
        assertStays(s.customer(), issue.key(), id, content);
    }

    @Test
    @DisplayName("S0-AC-61 the reporter gets 403 FORBIDDEN for an attachment an agent uploaded, and it stays")
    void reporterMayNotDeleteStaffAttachment() {
        Scene s = steps.scene();
        Session agent = fixtures.agent(s.admin(), s.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        byte[] content = bytes(100, 4);
        String id = upload(agent, issue.key(), "from-support.bin", content).expectStatus(201).jsonText("id");

        ApiResponse rejected = s.customer().client().delete(path(s.customer(), issue.key(), id)).send();

        assertThat(rejected.status()).isEqualTo(403);
        assertThat(rejected.errorCode()).isEqualTo("FORBIDDEN");
        assertStays(agent, issue.key(), id, content);
    }

    private void assertStays(Session owner, String key, String id, byte[] content) {
        assertThat(download(owner, key, id).body()).isEqualTo(content);
        JsonNode item = entry(owner, key, id);
        assertThat(item).isNotNull();
        assertThat(item.path("deleted").booleanValue()).isFalse();
        assertThat(rows.byId(id).state()).isEqualTo("stored");
    }
}
