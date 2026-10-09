package io.popdesk.helpdesk.attachment.storage;

import java.io.InputStream;
import java.util.UUID;

/**
 * Where attachment file contents live (architecture.md "Attachment storage"). Reading is customer data
 * without a scope, so only {@code io.popdesk.helpdesk.attachment.AttachmentService} calls the read
 * methods, after it checked the viewer's scope (S0-AC-84).
 */
public interface AttachmentStorage {

    /** Writes the contents of a new attachment, streamed: never the whole file in memory. */
    void write(UUID attachmentId, InputStream contents, long size);

    /** Up to {@code length} bytes of the contents from {@code offset} (0-based). */
    byte[] read(UUID attachmentId, long offset, int length);

    /** Removes the contents; nothing happens when there are none. */
    void delete(UUID attachmentId);
}
