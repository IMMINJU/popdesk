package io.popdesk.helpdesk.attachment.storage;

import java.io.InputStream;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * One place that holds attachment file contents: the table {@code attachment_content}, a folder, or a
 * bucket (architecture.md "Attachment storage"). {@link AttachmentStorages} picks the place a row
 * names; nothing outside this package holds one.
 *
 * <p>A failure is a {@link StorageException}: {@link StorageFileMissingException} when a read finds no
 * contents, {@link StorageUnreachableException} when the place gave no answer at all.
 */
interface AttachmentStorage {

    /** The storage kind recorded on each attachment row: {@code db}, {@code disk}, or {@code s3}. */
    String kind();

    /** This place as it is recorded on a row (S0-AC-56): the table, the folder, or bucket and prefix. */
    String location();

    /** Writes the contents of a new attachment, streamed: never the whole file in memory. */
    void write(UUID attachmentId, InputStream contents, long size);

    /** Up to {@code length} bytes of the contents from {@code offset} (0-based); fewer at the end. */
    byte[] read(UUID attachmentId, long offset, int length);

    /** Removes the contents; nothing happens when there are none (S0-AC-62). */
    void delete(UUID attachmentId);

    /** How many bytes of contents the attachment has here, or empty when it has none. Reads no contents. */
    OptionalLong size(UUID attachmentId);
}
