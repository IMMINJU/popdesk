package io.popdesk.helpdesk.attachment;

import io.popdesk.ApiError;
import io.popdesk.ApiException;
import io.popdesk.PageRequest;
import io.popdesk.Paged;
import io.popdesk.TextInput;
import io.popdesk.Transactions;
import io.popdesk.helpdesk.AccountRateLimit;
import io.popdesk.helpdesk.IssueStatus;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.attachment.AttachmentRepository.AttachmentRow;
import io.popdesk.helpdesk.attachment.storage.AttachmentStorage;
import io.popdesk.helpdesk.comment.CommentRepository;
import io.popdesk.helpdesk.comment.CommentRepository.CommentRow;
import io.popdesk.helpdesk.company.CompanyRepository;
import io.popdesk.helpdesk.issue.IssueKey;
import io.popdesk.helpdesk.issue.IssueRepository;
import io.popdesk.helpdesk.issue.IssueRepository.IssueRow;
import io.popdesk.notification.OpsAlerts;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

/**
 * Attachments of issues (ATT-1 to ATT-11). The one place that reads file contents from the
 * {@link AttachmentStorage}, and only after it found the attachment within the viewer's scope
 * (S0-AC-84, S0 premortem 10).
 *
 * <p>An upload has three steps (S0-AC-56): ① the {@code pending} row is committed, ② the contents are
 * written to the storage, ③ a business transaction locks the issue row and turns the row
 * {@code stored}. ① and ② finish before ③ begins, never inside it. Everything that can refuse the upload
 * (rate limit, scope, permission, input, limits) is checked before ①, so a refused upload leaves no
 * row and no contents (tasks.md section 7). A failure after ① leaves the row {@code pending} for the
 * cleanup.
 */
@Service
public class AttachmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    /** The largest file (ATT-2): 10 MB. */
    static final long MAX_FILE = DataSize.ofMegabytes(10).toBytes();

    /** The largest sum of the public attachments of one issue (ATT-2): 50 MB. */
    static final long MAX_PUBLIC_PER_ISSUE = DataSize.ofMegabytes(50).toBytes();

    static final int NAME_MAX = 255;
    static final int CONTENT_TYPE_MAX = 255;

    /** The setting with the team's limit (ATT-3). */
    static final String QUOTA_KEY = "POPDESK_ATTACHMENT_QUOTA";

    /** A file in a request, as the service checks and stores it. */
    private record Received(MultipartFile file, String name, String contentType) {}

    /** A downloadable attachment: its row and its contents. */
    public record Download(AttachmentRow attachment, AttachmentContents contents) {}

    private final AttachmentRepository attachments;
    private final IssueRepository issues;
    private final CommentRepository comments;
    private final CompanyRepository companies;
    private final AttachmentStorage storage;
    private final AccountRateLimit rateLimit;
    private final Transactions transactions;
    private final OpsAlerts alerts;
    private final Clock clock;
    private final long quota;

    AttachmentService(AttachmentRepository attachments, IssueRepository issues, CommentRepository comments,
            CompanyRepository companies, AttachmentStorage storage, AccountRateLimit rateLimit,
            Transactions transactions, OpsAlerts alerts, Clock clock, Environment environment) {
        this.attachments = attachments;
        this.issues = issues;
        this.comments = comments;
        this.companies = companies;
        this.storage = storage;
        this.rateLimit = rateLimit;
        this.transactions = transactions;
        this.alerts = alerts;
        this.clock = clock;
        this.quota = DataSize.parse(environment.getProperty(QUOTA_KEY, "10GB")).toBytes();
    }

    // Uploads

    /**
     * Attaches a file to an issue, or to the caller's own comment on it. Customers attach to issues they
     * reported while they still belong to the company, like comments (S0-AC-52); staff attach to issues
     * they can see. Checks in conventions order: the rate limit (S0-AC-65), the issue in scope (404),
     * permission (403), input (400), then the issue's state and the limits (400).
     */
    public AttachmentRow upload(Viewer viewer, String key, MultipartFile file, Long commentId) {
        rateLimit.count(AccountRateLimit.Action.ATTACHMENT, viewer.accountId());
        IssueKey issueKey = IssueKey.parse(key).orElseThrow(ApiException::notFound);
        IssueRow issue = issues.findByKey(viewer, issueKey).orElseThrow(ApiException::notFound);
        if (viewer.customer() && (issue.reporterId() != viewer.accountId()
                || companies.findByCode(viewer, issue.companyCode()).isEmpty())) {
            throw ApiException.forbidden();
        }
        List<ApiError.FieldError> errors = new ArrayList<>();
        Received received = received("file", file, errors);
        boolean internal = false;
        if (commentId != null) {
            CommentRow comment = comments.find(viewer, issue.id(), commentId)
                    .filter(c -> c.authorId() == viewer.accountId()).orElse(null);
            if (comment == null) {
                errors.add(new ApiError.FieldError("commentId", "UNKNOWN_VALUE"));
            } else {
                internal = comment.internal();
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors);
        }
        checkSize(file);
        if (issue.status() == IssueStatus.CLOSED) {
            throw issueClosed();
        }
        if (!internal && attachments.publicBytes(issue.id()) + file.getSize() > MAX_PUBLIC_PER_ISSUE) {
            throw issueAttachmentsTooLarge();
        }
        long before = attachments.storedBytes();
        checkQuota(before + file.getSize());

        UUID id = writePending(viewer, issue.id(), commentId, List.of(received)).getFirst();
        AttachmentRow stored = transactions.run(() -> {
            IssueRow locked = issues.lockByKey(viewer, issueKey).orElseThrow(ApiException::notFound);
            if (locked.status() == IssueStatus.CLOSED) {
                throw issueClosed();
            }
            if (attachments.updateStored(id, locked.id()) != 1) {
                return null;
            }
            return attachments.find(viewer, locked.id(), id).orElseThrow();
        });
        if (stored == null) {
            // The cleanup ended the pending row while step ② ran; the contents written since go too.
            storage.delete(id);
            throw new IllegalStateException("The attachment was ended before it was stored");
        }
        reviewQuota();
        return stored;
    }

    /**
     * Steps ① and ② for the files of a new issue (S0-AC-56), after {@code IssueService.prepare} checked
     * the filing: every file is checked first (field {@code files}), so a refused file leaves no row
     * (S0-AC-63). Returns the IDs of the pending rows for {@link #storeFiled}.
     */
    public List<UUID> writeFiling(Viewer viewer, List<MultipartFile> files) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        List<Received> received = new ArrayList<>();
        for (MultipartFile file : files) {
            received.add(received("files", file, errors));
        }
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors.stream().distinct().toList());
        }
        long total = 0;
        for (MultipartFile file : files) {
            checkSize(file);
            total += file.getSize();
        }
        if (total > MAX_PUBLIC_PER_ISSUE) {
            throw issueAttachmentsTooLarge();
        }
        checkQuota(attachments.storedBytes() + total);
        return writePending(viewer, null, null, received);
    }

    /**
     * Step ③ for the files of a new issue, inside the filing transaction after nothing but the lock
     * order's step 1: the pending rows become stored on the issue, in ID order (architecture.md
     * "Concurrency and lock order", step 2).
     */
    public void storeFiled(List<UUID> ids, UUID issueId) {
        for (UUID id : ids.stream().sorted().toList()) {
            if (attachments.updateStored(id, issueId) != 1) {
                throw new IllegalStateException("An attachment of the filing was ended before it was stored");
            }
        }
    }

    /**
     * After the filing transaction failed, outside it and before the response: removes the contents of
     * the filing's files whose rows the cleanup ended ({@code purging} or {@code purged}). It ended them
     * while step ② was still writing, and it never comes back to a row it ended, so nothing else would
     * remove what step ② wrote afterwards. Rows that are still {@code pending} stay for the cleanup
     * (S0-AC-63). A {@code stored} row is never touched: the filing can fail for the caller after its
     * transaction was committed, and then the issue has the file.
     */
    public void discardEnded(List<UUID> ids) {
        for (UUID id : ids) {
            try {
                if (attachments.isEnded(id)) {
                    storage.delete(id);
                }
            } catch (RuntimeException e) {
                log.warn("Removing the file of a failed filing failed ({}).", e.getClass().getSimpleName());
            }
        }
    }

    /**
     * After attachments were stored: over 80% of the quota, a warning in the log and the operations alert
     * for the admins (S0-AC-58, S0-AC-80). The alert adds no item while it is raised already.
     */
    public void reviewQuota() {
        long used = attachments.storedBytes();
        if (overWarning(used)) {
            log.warn("Attachments use {}% of {}; raise the setting or delete attachments.", used * 100 / quota,
                    QUOTA_KEY);
            alerts.raise(OpsAlerts.Kind.ATTACHMENT_QUOTA_WARNING);
        }
    }

    // Reading

    /** One page of the attachments of the issue the viewer may see, deleted ones as traces (S0-AC-60, S0-AC-61). */
    @Transactional(readOnly = true)
    public Paged<AttachmentRow> list(Viewer viewer, String key, int page, int size) {
        IssueRow issue = issue(viewer, key);
        PageRequest request = PageRequest.of(page, size);
        return request.of(attachments.list(viewer, issue.id(), request.offset(), request.size()),
                attachments.count(viewer, issue.id()));
    }

    /**
     * The stored attachment, if the viewer may see it; else 404 for every reason alike (S0-AC-102). The
     * ID comes as the path had it: one that is not a UUID is an ID no attachment has.
     */
    public AttachmentRow get(Viewer viewer, String key, String id) {
        UUID attachmentId = attachmentId(id).orElseThrow(ApiException::notFound);
        return attachments.find(viewer, issue(viewer, key).id(), attachmentId).orElseThrow(ApiException::notFound);
    }

    /** The UUID that is written exactly as {@code id}, in either letter case; no other spelling of it counts. */
    static Optional<UUID> attachmentId(String id) {
        if (id == null || id.length() != 36) {
            return Optional.empty();
        }
        try {
            UUID parsed = UUID.fromString(id);
            return parsed.toString().equalsIgnoreCase(id) ? Optional.of(parsed) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * The attachment and its contents after the scope check of {@link #get}. The contents are read in
     * pieces of 1 MB while the response is written, each piece its own statement (S0-AC-59).
     */
    public Download download(Viewer viewer, String key, String id) {
        AttachmentRow attachment = get(viewer, key, id);
        return new Download(attachment, new AttachmentContents(attachment.size(),
                (offset, length) -> storage.read(attachment.id(), offset, length)));
    }

    // Deleting

    /**
     * Deletes an attachment (S0-AC-61): the uploader or an admin. A customer deletes only while they
     * still belong to the issue's company, like comments and uploads (S1A-AC-54). The row becomes
     * {@code purging} and is committed, then the file goes and the row becomes {@code purged}. When
     * removing the file fails the delete still succeeds; the row stays {@code purging} and the cleanup
     * finishes it.
     */
    public void delete(Viewer viewer, String key, String attachmentId) {
        UUID id = attachmentId(attachmentId).orElseThrow(ApiException::notFound);
        IssueRow issue = issue(viewer, key);
        AttachmentRow attachment = attachments.find(viewer, issue.id(), id).orElseThrow(ApiException::notFound);
        if (!viewer.admin() && attachment.uploadedById() != viewer.accountId()) {
            throw ApiException.forbidden();
        }
        if (viewer.customer() && companies.findByCode(viewer, issue.companyCode()).isEmpty()) {
            throw ApiException.forbidden();
        }
        if (attachments.updatePurging(id, viewer.accountId(), now()) != 1) {
            throw ApiException.notFound();
        }
        try {
            storage.delete(id);
            attachments.updatePurged(id);
        } catch (RuntimeException e) {
            log.warn("Removing the file of a deleted attachment failed ({}); the attachment cleanup tries again.",
                    e.getClass().getSimpleName());
        }
        if (!overWarning(attachments.storedBytes())) {
            alerts.resolve(OpsAlerts.Kind.ATTACHMENT_QUOTA_WARNING);
        }
    }

    // Steps and checks

    /** Steps ① (one transaction for all rows) and ② (one statement per file). Returns the new IDs. */
    private List<UUID> writePending(Viewer viewer, UUID issueId, Long commentId, List<Received> files) {
        Instant now = now();
        List<PendingAttachment> rows = files.stream()
                .map(f -> {
                    UUID id = UUID.randomUUID();
                    return new PendingAttachment(id, issueId, commentId, f.name(), f.file().getSize(),
                            f.contentType(), storage.kind(), storage.location(id), viewer.accountId(), now);
                })
                .toList();
        transactions.run(() -> rows.forEach(attachments::persistPending));
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            UUID id = rows.get(i).id();
            MultipartFile file = files.get(i).file();
            try {
                storage.write(id, file.getInputStream(), file.getSize());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            ids.add(id);
        }
        return ids;
    }

    /** The file's name (1 to 255 characters, as sent) and content type; a bad name adds to {@code errors}. */
    private static Received received(String field, MultipartFile file, List<ApiError.FieldError> errors) {
        String name = file.getOriginalFilename();
        if (name == null || name.isEmpty()) {
            errors.add(new ApiError.FieldError(field, "REQUIRED"));
        } else if (TextInput.length(name) > NAME_MAX) {
            errors.add(new ApiError.FieldError(field, "TOO_LONG"));
        }
        return new Received(file, name, contentType(file.getContentType()));
    }

    /** The content type as sent, or {@code application/octet-stream} when it is missing or not one. */
    private static String contentType(String sent) {
        if (sent == null || sent.isBlank() || sent.length() > CONTENT_TYPE_MAX) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        try {
            return MediaType.parseMediaType(sent).toString();
        } catch (InvalidMediaTypeException e) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
    }

    private static void checkSize(MultipartFile file) {
        if (file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FILE_EMPTY", "The file is empty.");
        }
        if (file.getSize() > MAX_FILE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FILE_TOO_LARGE", "The file is over 10 MB.");
        }
    }

    /** The team limit (S0-AC-58), counted without a lock: concurrent uploads may pass it a little. */
    private void checkQuota(long after) {
        if (after > quota) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_QUOTA_EXCEEDED",
                    "The attachments of the team would pass their size limit.");
        }
    }

    private boolean overWarning(long used) {
        return used * 5 > quota * 4;
    }

    private IssueRow issue(Viewer viewer, String key) {
        return IssueKey.parse(key).flatMap(k -> issues.findByKey(viewer, k)).orElseThrow(ApiException::notFound);
    }

    private static ApiException issueClosed() {
        return new ApiException(HttpStatus.BAD_REQUEST, "ISSUE_CLOSED", "This issue is closed.");
    }

    private static ApiException issueAttachmentsTooLarge() {
        return new ApiException(HttpStatus.BAD_REQUEST, "ISSUE_ATTACHMENTS_TOO_LARGE",
                "The public attachments of the issue would pass 50 MB.");
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
