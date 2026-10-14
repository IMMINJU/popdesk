package io.popdesk.helpdesk.attachment.storage;

import java.util.UUID;

/**
 * The names of what popdesk keeps in a folder or a bucket (design 5b section 8). A file's path and key
 * are made of its attachment ID and nothing else: the name and the type a client sent never reach them
 * (S5B-AC-03, TM-20). Pure: no method here touches a storage.
 */
final class StoragePaths {

    /** The marker popdesk leaves in a place when it first uses it. */
    static final String MARKER = ".popdesk-storage";

    /** What a start writes and removes to see that the storage of new files can be written. */
    static final String WRITE_CHECK = ".popdesk-write-check";

    /** The first part of every object key, and the end of an {@code s3} row's location. */
    static final String PREFIX = "popdesk";

    private static final String PART = ".part";

    private StoragePaths() {}

    /**
     * Where {@code disk} keeps the file of an attachment, from the storage folder, with {@code /}
     * between the two parts: the first two characters of the ID as a folder, then the ID, as
     * {@link UUID#toString} writes it (36 characters, lowercase). For example
     * {@code 3f/3f2b6c1e-0000-4000-8000-000000000001}.
     *
     * @param attachmentId the attachment's ID; null is a {@link NullPointerException}
     * @return the path, 39 characters, never null
     */
    public static String filePath(UUID attachmentId) {
        String id = attachmentId.toString();
        return id.substring(0, 2) + "/" + id;
    }

    /**
     * The temporary name {@code disk} writes a file under before it moves it to {@link #filePath}:
     * that path with {@code .part} at its end, in the same folder.
     *
     * @param attachmentId the attachment's ID; null is a {@link NullPointerException}
     * @return the path, never null
     */
    public static String partPath(UUID attachmentId) {
        return filePath(attachmentId) + PART;
    }

    /**
     * The key of an attachment's object in a bucket: the prefix, {@code /}, and the ID as
     * {@link UUID#toString} writes it. For example {@code popdesk/3f2b6c1e-0000-4000-8000-000000000001}.
     *
     * @param prefix the prefix of the row's location, taken as it is; it is not checked here, and null
     *     gives a key that starts with {@code null/}
     * @param attachmentId the attachment's ID; null is a {@link NullPointerException}
     * @return the key, never null
     */
    public static String objectKey(String prefix, UUID attachmentId) {
        return prefix + "/" + attachmentId.toString();
    }
}
