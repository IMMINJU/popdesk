package io.popdesk.helpdesk.attachment;

import io.popdesk.ApiException;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.attachment.AttachmentRepository.AttachmentRow;
import io.popdesk.helpdesk.attachment.storage.AttachmentStorage;
import io.popdesk.helpdesk.issue.IssueKey;
import io.popdesk.helpdesk.issue.IssueRepository;
import io.popdesk.helpdesk.issue.IssueRepository.IssueRow;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Attachments of issues. The one place that reads file contents from the {@link AttachmentStorage},
 * and only after it found the attachment within the viewer's scope (S0-AC-84, S0 premortem 10).
 */
@Service
public class AttachmentService {

    private final AttachmentRepository attachments;
    private final IssueRepository issues;
    private final AttachmentStorage storage;

    AttachmentService(AttachmentRepository attachments, IssueRepository issues, AttachmentStorage storage) {
        this.attachments = attachments;
        this.issues = issues;
        this.storage = storage;
    }

    /** The attachment, if the viewer may see it; else 404 for every reason alike (COM-1). */
    public AttachmentRow get(Viewer viewer, String key, UUID id) {
        IssueRow issue = IssueKey.parse(key).flatMap(k -> issues.findByKey(viewer, k))
                .orElseThrow(ApiException::notFound);
        return attachments.find(viewer, issue.id(), id).orElseThrow(ApiException::notFound);
    }

    /** Up to {@code length} bytes of the contents from {@code offset}, after the scope check of {@link #get}. */
    public byte[] read(Viewer viewer, String key, UUID id, long offset, int length) {
        AttachmentRow attachment = get(viewer, key, id);
        return storage.read(attachment.id(), offset, length);
    }
}
