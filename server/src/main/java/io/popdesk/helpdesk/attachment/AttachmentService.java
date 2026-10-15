package io.popdesk.helpdesk.attachment;

import io.popdesk.ApiError;
import io.popdesk.ApiErrors;
import io.popdesk.ApiException;
import io.popdesk.PageRequest;
import io.popdesk.Paged;
import io.popdesk.TextInput;
import io.popdesk.Transactions;
import io.popdesk.helpdesk.AccountRateLimit;
import io.popdesk.helpdesk.IssueStatus;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.attachment.AttachmentRepository.AttachmentRow;
import io.popdesk.helpdesk.attachment.AttachmentRepository.FilePlace;
import io.popdesk.helpdesk.attachment.storage.AttachmentStorages;
import io.popdesk.helpdesk.comment.CommentRepository;
import io.popdesk.helpdesk.comment.CommentRepository.CommentRow;
import io.popdesk.helpdesk.company.CompanyRepository;
import io.popdesk.helpdesk.issue.ImageIds;
import io.popdesk.helpdesk.issue.IssueAttachments;
import io.popdesk.helpdesk.issue.IssueHistoryRepository;
import io.popdesk.helpdesk.issue.IssueHistoryRepository.Field;
import io.popdesk.helpdesk.issue.IssueKey;
import io.popdesk.helpdesk.issue.IssueRepository;
import io.popdesk.helpdesk.issue.IssueRepository.IssueRow;
import io.popdesk.notification.OpsAlerts;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
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
 * {@link AttachmentStorages}, and only after it found the attachment within the viewer's scope
 * (S0-AC-84, S0 premortem 10).
 *
 * <p>A new file goes to the storage the settings name. A file that is there already is read and removed
 * where its row says it is (ATT-9, S5B-AC-10).
 *
 * <p>An upload has three steps (S0-AC-56): ① the {@code pending} row is committed, ② the contents are
 * written to the storage, ③ a business transaction locks the issue row and turns the row
 * {@code stored}. ① and ② finish before ③ begins, never inside it. Everything that can refuse the upload
 * (rate limit, scope, permission, input, limits) is checked before ①, so a refused upload leaves no
 * row and no contents (tasks.md section 7). A failure after ① leaves the row {@code pending} for the
 * cleanup.
 *
 * <p>An attachment that is added to an issue that is already there, or deleted, leaves an entry in the
 * issue's history, written in the transaction that adds or deletes it (ATT-8, S5-AC-45). The entry
 * holds the attachment's ID and never its name.
 *
 * <p>A temporary image (ATT-6, ATT-7) is uploaded the same way without an issue, and until a save
 * attaches it only the account that uploaded it reads it, here and nowhere else.
 */
@Service
public class AttachmentService implements IssueAttachments {

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    /** The largest file (ATT-2): 10 MB. */
    static final long MAX_FILE = DataSize.ofMegabytes(10).toBytes();

    /** The largest sum of the public attachments of one issue (ATT-2): 50 MB. */
    static final long MAX_PUBLIC_PER_ISSUE = DataSize.ofMegabytes(50).toBytes();

    /** The most files one filing carries (S1A-AC-65). */
    static final int MAX_FILES_PER_FILING = 20;

    static final int NAME_MAX = 255;
    static final int CONTENT_TYPE_MAX = 255;

    /** How long a temporary image is read and can be attached, from its upload (ATT-7). */
    public static final Duration IMAGE_LIFETIME = Duration.ofHours(24);

    /** The most temporary images one account has at a time, and their bytes (ATT-7): 20 and 50 MB. */
    static final int MAX_TEMPORARY_IMAGES = 20;
    static final long MAX_TEMPORARY_BYTES = DataSize.ofMegabytes(50).toBytes();

    /** The setting with the team's limit (ATT-3). */
    static final String QUOTA_KEY = "POPDESK_ATTACHMENT_QUOTA";

    /** A file in a request, as the service checks and stores it. */
    private record Received(MultipartFile file, String name, String contentType) {}

    /** A downloadable attachment: its row and its contents. */
    public record Download(AttachmentRow attachment, AttachmentContents contents) {}

    private final AttachmentRepository attachments;
    private final IssueRepository issues;
    private final IssueHistoryRepository history;
    private final CommentRepository comments;
    private final CompanyRepository companies;
    private final AttachmentStorages storage;
    private final AccountRateLimit rateLimit;
    private final Transactions transactions;
    private final OpsAlerts alerts;
    private final Clock clock;
    private final long quota;

    AttachmentService(AttachmentRepository attachments, IssueRepository issues, IssueHistoryRepository history,
            CommentRepository comments, CompanyRepository companies, AttachmentStorages storage, AccountRateLimit rateLimit,
            Transactions transactions, OpsAlerts alerts, Clock clock, Environment environment) {
        this.attachments = attachments;
        this.issues = issues;
        this.history = history;
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
     * Attaches a file to an issue, or to the caller's own comment on it; a comment that is deleted takes
     * no file and is the same {@code UNKNOWN_VALUE} as one that is not the caller's. Customers attach to
     * issues they reported while they still belong to the company, like comments (S0-AC-52); staff
     * attach to issues they can see. Checks in conventions order: the rate limit (S0-AC-65), the issue in scope (404),
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
                    .filter(c -> c.authorId() == viewer.accountId() && !c.deleted()).orElse(null);
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

        UUID id = writePending(viewer, issue.id(), commentId, false, List.of(received)).getFirst();
        AttachmentRow stored = transactions.run(() -> {
            IssueRow locked = issues.lockByKey(viewer, issueKey).orElseThrow(ApiException::notFound);
            if (locked.status() == IssueStatus.CLOSED) {
                throw issueClosed();
            }
            if (attachments.updateStored(id, locked.id()) != 1) {
                return null;
            }
            history.insertChanged(locked.id(), Field.ATTACHMENT, null, id.toString(), viewer.accountId(), now());
            return attachments.find(viewer, locked.id(), id).orElseThrow();
        });
        if (stored == null) {
            // The cleanup ended the pending row while step ② ran; the contents written since go too.
            deleteNew(id);
            throw new IllegalStateException("The attachment was ended before it was stored");
        }
        reviewQuota();
        return stored;
    }

    /**
     * The first check of a filing with files, before the issue is looked at and before any file is
     * written: more than {@value #MAX_FILES_PER_FILING} files is 413 (S1A-AC-65), and every file counts
     * toward the account's upload limit, all of them or none (S1A-AC-66). A refused filing counts nothing.
     */
    public void countFiling(Viewer viewer, List<MultipartFile> files) {
        if (files.size() > MAX_FILES_PER_FILING) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, ApiErrors.PAYLOAD_TOO_LARGE,
                    ApiErrors.PAYLOAD_TOO_LARGE_MESSAGE);
        }
        rateLimit.count(AccountRateLimit.Action.ATTACHMENT, viewer.accountId(), files.size());
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
        return writePending(viewer, null, null, false, received);
    }

    /**
     * Step ③ for the files of a new issue, inside the filing transaction after nothing but the lock
     * order's step 1: the pending rows become stored on the issue, in ID order (architecture.md
     * "Concurrency and lock order", step 2). They leave no history entry of their own (S5-AC-42).
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
                    deleteNew(id);
                }
            } catch (RuntimeException e) {
                log.warn("Removing the file of a failed filing failed ({}).", e.getClass().getSimpleName());
            }
        }
    }

    /**
     * Stores an image as a temporary image of the caller, before there is an issue or a comment to put
     * it on (S5-AC-01), and returns its ID. Checks in the order of {@link #upload}: the rate limit, which
     * is the one of attachment uploads (S5-AC-07), the file's name, its size, then what only an image
     * has: the format, read from the first bytes and nothing else (S5-AC-02), and the caller's 20 images
     * and 50 MB (S5-AC-05), counted without a lock like the team limit, which comes last (S5-AC-06).
     *
     * <p>The three steps of an upload, with a step ③ that locks nothing: there is no issue. The content
     * type stored is the one of the format, whatever the request said (S5-AC-03).
     */
    public UUID uploadImage(Viewer viewer, MultipartFile file) {
        rateLimit.count(AccountRateLimit.Action.ATTACHMENT, viewer.accountId());
        List<ApiError.FieldError> errors = new ArrayList<>();
        Received received = received("file", file, errors);
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors);
        }
        checkSize(file);
        ImageFormat format = ImageFormat.of(head(file)).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                "FILE_NOT_IMAGE", "The file is not a PNG, JPEG, GIF, or WebP image."));
        AttachmentRepository.TemporaryUsage usage = attachments.temporaryUsage(viewer.accountId(), imagesExpired());
        if (usage.images() + 1 > MAX_TEMPORARY_IMAGES || usage.bytes() + file.getSize() > MAX_TEMPORARY_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TEMPORARY_IMAGE_LIMIT_EXCEEDED",
                    "Too many images are waiting to be saved with an issue or a comment.");
        }
        checkQuota(attachments.storedBytes() + file.getSize());

        UUID id = writePending(viewer, null, null, true,
                List.of(new Received(file, received.name(), format.contentType()))).getFirst();
        if (attachments.updateStoredTemporary(id) != 1) {
            // The cleanup ended the pending row while step ② ran; the contents written since go too.
            deleteNew(id);
            throw new IllegalStateException("The image was ended before it was stored");
        }
        reviewQuota();
        return id;
    }

    /**
     * Attaches temporary images with a save (S5-AC-20), inside the caller's transaction: one UPDATE
     * for all of them, and when it did not attach every one, nothing is attached. Which ID it was and
     * why is not told (S5-AC-21). The 50 MB of public attachments are counted after the UPDATE, under
     * the caller's lock on the issue row, the images of this request among them (S5-AC-23).
     */
    @Override
    public void attachImages(long uploadedBy, UUID issueId, Long commentId, boolean internal, boolean filing,
            ImageIds images, Instant now) {
        if (images.isEmpty()) {
            return;
        }
        SortedSet<UUID> ids = new TreeSet<>();
        for (String value : images.values()) {
            ids.add(attachmentId(value).orElseThrow(AttachmentService::imageInvalid));
        }
        List<UUID> attached = attachments.updateAttached(ids, uploadedBy, issueId, commentId,
                now.minus(IMAGE_LIFETIME));
        if (attached.size() != ids.size()) {
            throw imageInvalid();
        }
        if (!internal && attachments.publicBytes(issueId) > MAX_PUBLIC_PER_ISSUE) {
            throw issueAttachmentsTooLarge();
        }
        if (!filing) {
            for (UUID id : ids) {
                history.insertChanged(issueId, Field.ATTACHMENT, null, id.toString(), uploadedBy, now);
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
        return new Download(attachment, contents(attachment));
    }

    /**
     * The temporary image, for the account that uploaded it; else 404, one answer for an ID nobody has,
     * another account's image, and an image that is attached, expired, or not stored yet (S5-AC-11,
     * S5-AC-12). An attached image is read under its issue, with {@link #get}.
     */
    public AttachmentRow temporaryImage(Viewer viewer, String id) {
        UUID imageId = attachmentId(id).orElseThrow(ApiException::notFound);
        return attachments.findTemporary(viewer, imageId, imagesExpired()).orElseThrow(ApiException::notFound);
    }

    /** The temporary image and its contents after the check of {@link #temporaryImage} (S5-AC-10). */
    public Download downloadTemporaryImage(Viewer viewer, String id) {
        AttachmentRow image = temporaryImage(viewer, id);
        return new Download(image, contents(image));
    }

    /**
     * The contents of a file the caller found in the viewer's scope, from the place its row names. The
     * first piece is read here, before there is a response: a file that is gone or cannot be read is an
     * error the caller answers with 500, never a response of the right length without its bytes
     * (S5B-AC-31).
     */
    private AttachmentContents contents(AttachmentRow attachment) {
        byte[] first = piece(attachment, 0, (int) Math.min(AttachmentContents.PIECE, attachment.size()));
        if (first.length == 0 && attachment.size() > 0) {
            log.error("The file of attachment {} in the {} storage is empty.", attachment.id(),
                    attachment.storage());
            throw new IllegalStateException("The attachment's contents ended before its size");
        }
        return new AttachmentContents(attachment.size(), first,
                (offset, length) -> piece(attachment, offset, length));
    }

    /** One piece of a file. A failure is logged with the attachment's ID and storage kind, never its name. */
    private byte[] piece(AttachmentRow attachment, long offset, int length) {
        try {
            return storage.read(attachment.storage(), attachment.storageLocation(), attachment.id(), offset,
                    length);
        } catch (RuntimeException e) {
            log.error("Reading the file of attachment {} from the {} storage failed ({}).", attachment.id(),
                    attachment.storage(), e.getClass().getSimpleName());
            throw e;
        }
    }

    // Deleting

    /**
     * Deletes an attachment (S0-AC-61): the uploader or an admin. A customer deletes only while they
     * still belong to the issue's company, like comments and uploads (S1A-AC-54). The issue row is
     * locked first, in the caller's scope, so a delete of the issue into the trash that came first is
     * seen here as 404 and one that comes later waits (S7A-AC-12); then the row becomes {@code purging}
     * and its history entry is written with it (S5-AC-41). That is committed, then the file goes and the
     * row becomes {@code purged}. When removing the file fails the delete still succeeds; the row stays
     * {@code purging} and the cleanup finishes it.
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
        Instant now = now();
        transactions.run(() -> {
            issues.lockByKey(viewer, IssueKey.parse(key).orElseThrow()).orElseThrow(ApiException::notFound);
            if (attachments.updatePurging(id, viewer.accountId(), now) != 1) {
                throw ApiException.notFound();
            }
            history.insertChanged(issue.id(), Field.ATTACHMENT, id.toString(), null, viewer.accountId(), now);
        });
        removeFiles(List.of(id));
    }

    /**
     * The first step of {@link #delete} for every stored attachment of a comment that is being deleted
     * (S4-AC-14), inside the caller's transaction and after it locked the issue row: the rows become
     * {@code purging} in ID order (architecture.md "Concurrency and lock order", step 2), with the trace
     * a deleted attachment leaves and an entry each in the issue's history (S5-AC-41). Returns their IDs
     * for {@link #removeFiles}.
     */
    @Override
    public List<UUID> purgeOfComment(UUID issueId, long commentId, long deletedBy, Instant at) {
        List<UUID> purging = new ArrayList<>();
        for (UUID id : attachments.storedOfComment(commentId)) {
            if (attachments.updatePurging(id, deletedBy, at) == 1) {
                purging.add(id);
            }
        }
        for (UUID id : purging) {
            history.insertChanged(issueId, Field.ATTACHMENT, id.toString(), null, deletedBy, at);
        }
        return purging;
    }

    /**
     * The rest of a delete, after the {@code purging} rows are committed and never inside a transaction:
     * the files go and the rows become {@code purged}. When removing a file fails the delete still
     * succeeds; the row stays {@code purging} and the cleanup finishes it.
     */
    @Override
    public void removeFiles(List<UUID> ids) {
        for (UUID id : ids) {
            try {
                FilePlace place = attachments.filePlace(id).orElseThrow();
                storage.delete(place.storage(), place.location(), id);
                attachments.updatePurged(id);
            } catch (RuntimeException e) {
                log.warn("Removing the file of a deleted attachment failed ({}); the attachment cleanup tries again.",
                        e.getClass().getSimpleName());
            }
        }
        if (!ids.isEmpty() && !overWarning(attachments.storedBytes())) {
            alerts.resolve(OpsAlerts.Kind.ATTACHMENT_QUOTA_WARNING);
        }
    }

    // Steps and checks

    /** Steps ① (one transaction for all rows) and ② (one statement per file). Returns the new IDs. */
    private List<UUID> writePending(Viewer viewer, UUID issueId, Long commentId, boolean inline,
            List<Received> files) {
        Instant now = now();
        List<PendingAttachment> rows = files.stream()
                .map(f -> {
                    UUID id = UUID.randomUUID();
                    return new PendingAttachment(id, issueId, commentId, f.name(), f.file().getSize(),
                            f.contentType(), storage.kind(), storage.location(), viewer.accountId(), now,
                            inline);
                })
                .toList();
        transactions.run(() -> rows.forEach(attachments::persistPending));
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            UUID id = rows.get(i).id();
            MultipartFile file = files.get(i).file();
            try (InputStream contents = file.getInputStream()) {
                storage.write(id, contents, file.getSize());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            ids.add(id);
        }
        return ids;
    }

    /** Removes what step ② wrote for a row of this request: it is in the storage of new files. */
    private void deleteNew(UUID id) {
        storage.delete(storage.kind(), storage.location(), id);
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

    /** The first bytes of the file, as many as tell an image's format. */
    private static byte[] head(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(ImageFormat.HEAD_LENGTH);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A temporary image created at this time or before is expired now (S5-AC-30). */
    private Instant imagesExpired() {
        return now().minus(IMAGE_LIFETIME);
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

    /** One answer for every image a save cannot attach, the same bytes each time (S5-AC-21). */
    private static ApiException imageInvalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "TEMPORARY_IMAGE_INVALID",
                "An image cannot be attached. Upload the images again.");
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
