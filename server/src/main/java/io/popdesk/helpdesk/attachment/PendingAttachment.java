package io.popdesk.helpdesk.attachment;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The metadata row an upload commits first, in state {@code pending} (step ① of S0-AC-56). The file
 * contents are not a field: they go to the storage afterwards, streamed (architecture.md "Attachment
 * storage"), so no entity ever holds them.
 */
@Entity
@Table(name = "attachment")
class PendingAttachment {

    @Id
    private UUID id;

    private long tenantId;

    /** Null for a file sent with a new issue: the issue row does not exist yet. */
    private UUID issueId;

    private Long commentId;

    private String fileName;

    private long size;

    private String contentType;

    private String state;

    private String storage;

    private String storageLocation;

    private long uploadedBy;

    private Instant createdAt;

    protected PendingAttachment() {}

    PendingAttachment(UUID id, UUID issueId, Long commentId, String fileName, long size, String contentType,
            String storage, String storageLocation, long uploadedBy, Instant createdAt) {
        this.id = id;
        this.tenantId = 1;
        this.issueId = issueId;
        this.commentId = commentId;
        this.fileName = fileName;
        this.size = size;
        this.contentType = contentType;
        this.state = "pending";
        this.storage = storage;
        this.storageLocation = storageLocation;
        this.uploadedBy = uploadedBy;
        this.createdAt = createdAt;
    }

    UUID id() {
        return id;
    }
}
