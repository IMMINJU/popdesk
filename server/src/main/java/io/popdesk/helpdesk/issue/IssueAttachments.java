package io.popdesk.helpdesk.issue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the writes of issues and comments ask of attachments. The attachment package implements it, so
 * {@code issue} and {@code comment} do not depend on that package and the packages have one direction:
 * {@code attachment} to {@code comment} to {@code issue} (ADR D-57).
 *
 * <p>The methods that take part in a write run inside the caller's transaction, after it locked the
 * issue row and before it inserts anything (architecture.md "Concurrency and lock order", step 2).
 */
public interface IssueAttachments {

    /**
     * Attaches temporary images to an issue with the save of its content or of one of its comments
     * (S5-AC-20): all of them or, with an exception, none. Only images that {@code uploadedBy} uploaded,
     * that are attached nowhere, and that are not expired at {@code now} can be attached; anything else
     * among the IDs, one that is no ID too, is 400 {@code TEMPORARY_IMAGE_INVALID}, one answer whatever
     * is wrong and with whichever ID (S5-AC-21). Then the public attachments of the issue must not pass
     * 50 MB with them: 400 {@code ISSUE_ATTACHMENTS_TOO_LARGE} (S5-AC-23). The caller's transaction is
     * rolled back by either. Each image leaves an entry in the issue's history (S5-AC-40), but while the
     * issue is being filed: its {@code CREATED} entry stands for what it was filed with (S5-AC-42).
     *
     * @param issueId the issue, whose row the caller locked, or the ID of the issue it is filing
     * @param commentId the comment the images belong to, which may be inserted after this call in the
     *     same transaction; null for images of the issue's content
     * @param internal whether that comment is internal: its images do not count toward the 50 MB
     * @param filing whether the issue is being filed in this transaction
     */
    void attachImages(long uploadedBy, UUID issueId, Long commentId, boolean internal, boolean filing,
            ImageIds images, Instant now);

    /**
     * Every stored attachment of a comment that is being deleted becomes {@code purging}, in ID order,
     * with the trace a deleted attachment leaves (S4-AC-14) and an entry each in the history of the
     * issue (S5-AC-41). Returns their IDs for {@link #removeFiles}.
     */
    List<UUID> purgeOfComment(UUID issueId, long commentId, long deletedBy, Instant at);

    /**
     * The rest of a delete, after the {@code purging} rows are committed and never inside a transaction:
     * the files go and the rows become {@code purged}.
     */
    void removeFiles(List<UUID> ids);
}
