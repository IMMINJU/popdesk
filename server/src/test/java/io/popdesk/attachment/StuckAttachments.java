package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.bytes;
import static io.popdesk.attachment.AttachmentSteps.path;
import static io.popdesk.attachment.AttachmentSteps.upload;

import io.popdesk.attachment.AttachmentRows.Row;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.DbInspector;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.List;

/**
 * Attachments left in {@code pending} or {@code purging}, made with {@link FailureTriggers}: an
 * upload whose step ③ fails stays {@code pending}; a delete whose file removal fails stays
 * {@code purging}. The triggers are dropped before these methods return.
 */
final class StuckAttachments {

    /** An attachment row the test made, and its content. */
    record Stuck(String id, String fileName, byte[] content) {}

    private final DbInspector db;
    private final AttachmentRows rows;

    StuckAttachments(DbInspector db) {
        this.db = db;
        this.rows = new AttachmentRows(db);
    }

    /** The attachment table, found from a normal upload by {@code session} to {@code key}. */
    String attachmentTable(Session session, String key) {
        String id = upload(session, key, Unique.text("probe") + ".bin", bytes(10, Unique.number())).expectStatus(201)
                .jsonText("id");
        return rows.byId(id).table();
    }

    /** An upload whose step ③ failed: 500 INTERNAL_ERROR, and its row stays {@code pending}. */
    Stuck pending(Session session, String key) {
        String table = attachmentTable(session, key);
        String name = Unique.text("pending") + ".bin";
        byte[] content = bytes(1_000, Unique.number());
        ApiResponse failed;
        try (FailureTriggers triggers = new FailureTriggers(db)) {
            triggers.failStoring(table, name);
            failed = upload(session, key, name, content);
        }
        failed.expectStatus(500);
        if (!"INTERNAL_ERROR".equals(failed.errorCode())) {
            throw new AssertionError("Expected INTERNAL_ERROR from " + failed.request() + ": " + failed.snippet());
        }
        List<Row> left = rows.byFileName(name);
        if (left.size() != 1 || !left.getFirst().state().equals("pending")) {
            throw new AssertionError("Expected one pending row for " + name + " but found " + left);
        }
        return new Stuck(left.getFirst().id(), name, content);
    }

    /** An attachment deleted while removing its file failed: 204, and its row stays {@code purging}. */
    Stuck purging(Session session, String key) {
        String name = Unique.text("purging") + ".bin";
        byte[] content = bytes(1_000, Unique.number());
        String id = upload(session, key, name, content).expectStatus(201).jsonText("id");
        try (FailureTriggers triggers = new FailureTriggers(db)) {
            triggers.failRemovingContent(content);
            session.client().delete(path(session, key, id)).expect(204);
        }
        String state = rows.byId(id).state();
        if (!state.equals("purging")) {
            throw new AssertionError("Expected attachment " + id + " to stay purging but it is " + state);
        }
        return new Stuck(id, name, content);
    }
}
