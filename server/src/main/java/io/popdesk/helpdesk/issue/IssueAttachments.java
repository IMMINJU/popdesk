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
     * Every stored attachment of a comment that is being deleted becomes {@code purging}, in ID order,
     * with the trace a deleted attachment leaves (S4-AC-14). Returns their IDs for {@link #removeFiles}.
     */
    List<UUID> purgeOfComment(long commentId, long deletedBy, Instant at);

    /**
     * The rest of a delete, after the {@code purging} rows are committed and never inside a transaction:
     * the files go and the rows become {@code purged}.
     */
    void removeFiles(List<UUID> ids);
}
