package io.popdesk.helpdesk.privacy;

import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.attachment.AttachmentContents;
import io.popdesk.helpdesk.attachment.AttachmentService;
import io.popdesk.helpdesk.privacy.CompanyExportRepository.AttachmentData;
import io.popdesk.helpdesk.privacy.CompanyExportRepository.CommentData;
import io.popdesk.helpdesk.privacy.CompanyExportRepository.Company;
import io.popdesk.helpdesk.privacy.CompanyExportRepository.IssueData;
import io.popdesk.helpdesk.privacy.CompanyExportRepository.IssueRef;
import io.popdesk.helpdesk.privacy.CompanyExportRepository.NoteData;
import io.popdesk.helpdesk.privacy.CompanyExportRepository.TaskData;
import io.popdesk.helpdesk.privacy.CompanyExportRepository.Writer;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the ZIP of a company export (S10B-AC-11 to S10B-AC-17, slice 10b design 0절 3 to 5, conventions.md
 * "Company export (PII-8)"), in two parts:
 *
 * <ol>
 *   <li>In one read-only {@code REPEATABLE READ} transaction, one snapshot (S10B-AC-17): the member customers, then per
 *       issue {@code issue.json} and {@code comments.json}, written into the ZIP as they are read, and the list of the
 *       attachment rows whose files go in.
 *   <li>With that transaction closed, so no database connection is held while the storage is read: the bytes of each
 *       file, through {@link AttachmentService#exportContents}. A file the storage no longer has is left out and
 *       listed in {@code manifest.json}; any other failure of the storage fails the export ({@code STORAGE}), so a
 *       ZIP never misses a file silently.
 * </ol>
 *
 * {@code manifest.json} comes last, with the counts. The ZIP is written as {@code {id}.zip.part} in the export folder
 * and renamed to {@code {id}.zip} once it is whole. The fields are written here, not through a portal or console
 * type, so a change of those answers never changes the ZIP (design.md 0절 4); staff are always the support team
 * (acceptance.md 5절 7, 5절 20).
 */
@Component
class CompanyExportWriter {

    /** Why writing stopped: a {@code failure} of the export (conventions.md). */
    static final class Failed extends RuntimeException {

        private final String failure;

        Failed(String failure, Throwable cause) {
            super(failure, cause);
            this.failure = failure;
        }

        String failure() {
            return failure;
        }
    }

    static final String STORAGE = "STORAGE";
    static final String DISK = "DISK";
    static final String UNEXPECTED = "UNEXPECTED";

    /** One export to write: its ID, the company, the two choices, and the time the ZIP is made. */
    record Job(UUID id, Company company, boolean includeInternal, boolean includePrivate, Instant exportedAt) {}

    /** The file names of an export in the export folder. */
    static Path zipOf(Path folder, UUID id) {
        return folder.resolve(id + ".zip");
    }

    static Path partOf(Path folder, UUID id) {
        return folder.resolve(id + ".zip.part");
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final DateTimeFormatter TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /** What the second part reads after the snapshot: a file and where it goes. */
    private record File(String path, AttachmentData row) {}

    /** The counts of {@code manifest.json}. */
    private static final class Counts {
        long issues;
        long comments;
        long attachments;
        long customers;
    }

    private final CompanyExportRepository repository;
    private final AttachmentService attachments;
    private final TransactionTemplate snapshot;

    CompanyExportWriter(CompanyExportRepository repository, AttachmentService attachments,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.attachments = attachments;
        this.snapshot = new TransactionTemplate(transactionManager);
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /**
     * Writes the export's ZIP into {@code folder}, which is made when it is not there.
     *
     * @return the ZIP's bytes
     * @throws Failed with {@code DISK} when the file cannot be written, {@code STORAGE} when a file of an attachment
     *     cannot be read for another reason than being gone, {@code UNEXPECTED} for anything else; the caller deletes
     *     what was written
     */
    long write(Job job, Path folder) {
        Path part = partOf(folder, job.id());
        Path zipPath = zipOf(folder, job.id());
        try {
            Files.createDirectories(folder);
        } catch (IOException | UncheckedIOException e) {
            throw new Failed(DISK, e);
        }
        try (OutputStream file = Files.newOutputStream(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(file), StandardCharsets.UTF_8)) {
            Counts counts = new Counts();
            ZipEntryNames names = new ZipEntryNames();
            List<File> files = snapshot.execute(status -> writeLists(job, zip, names, counts));
            List<AttachmentData> missing = new ArrayList<>();
            for (File f : files == null ? List.<File>of() : files) {
                if (addFile(zip, f)) {
                    counts.attachments++;
                } else {
                    missing.add(f.row());
                }
            }
            writeManifest(zip, job, counts, missing);
        } catch (IOException | UncheckedIOException e) {
            throw new Failed(DISK, e);
        } catch (JacksonException e) {
            throw new Failed(e.getCause() instanceof IOException ? DISK : UNEXPECTED, e);
        } catch (Failed e) {
            throw e;
        } catch (RuntimeException e) {
            throw new Failed(UNEXPECTED, e);
        }
        try {
            Files.move(part, zipPath, StandardCopyOption.ATOMIC_MOVE);
            return Files.size(zipPath);
        } catch (IOException e) {
            throw new Failed(DISK, e);
        }
    }

    /** The first part, in the snapshot: {@code customers.json}, then each issue's two files; gives the files to add. */
    private List<File> writeLists(Job job, ZipOutputStream zip, ZipEntryNames names, Counts counts) {
        long companyId = job.company().id();
        String code = job.company().code();
        List<String[]> customers = repository.listCustomers(companyId);
        entry(zip, "customers.json", json -> {
            json.writeStartArray();
            for (String[] customer : customers) {
                json.writeStartObject();
                json.writeStringProperty("email", customer[0]);
                json.writeStringProperty("name", customer[1]);
                json.writeEndObject();
            }
            json.writeEndArray();
        });
        counts.customers = customers.size();
        List<File> files = new ArrayList<>();
        for (IssueRef ref : repository.listIssues(companyId, job.includePrivate())) {
            String key = code + "-" + ref.number();
            String folder = ZipEntryNames.issue(key);
            IssueData issue = repository.findIssue(ref.id());
            entry(zip, folder + "issue.json", json -> writeIssue(json, key, issue));
            List<CommentData> comments = repository.listComments(ref.id(), job.includeInternal());
            List<TaskData> tasks = job.includeInternal() ? repository.listTasks(ref.id()) : List.of();
            List<NoteData> notes = repository.listNotes(tasks.stream().map(TaskData::id).toList());
            entry(zip, folder + "comments.json", json -> writeComments(json, comments, tasks, notes));
            for (AttachmentData row : repository.listAttachments(ref.id(), job.includeInternal())) {
                files.add(new File(names.file(key, row.id().toString(), row.fileName()), row));
            }
            counts.issues++;
            counts.comments += comments.size();
        }
        return files;
    }

    private static void writeIssue(JsonGenerator json, String key, IssueData issue) {
        json.writeStartObject();
        json.writeStringProperty("key", key);
        json.writeStringProperty("title", issue.title());
        json.writeStringProperty("content", issue.content());
        json.writeStringProperty("status", issue.status());
        json.writeStringProperty("priority", issue.priority());
        json.writeBooleanProperty("private", issue.privateIssue());
        person(json, "reporter", issue.reporter());
        json.writeBooleanProperty("filedBySupport", issue.filedBySupport());
        time(json, "createdAt", issue.createdAt());
        time(json, "updatedAt", issue.updatedAt());
        time(json, "resolvedAt", issue.resolvedAt());
        time(json, "closedAt", issue.closedAt());
        json.writeEndObject();
    }

    private static void writeComments(JsonGenerator json, List<CommentData> comments, List<TaskData> tasks,
            List<NoteData> notes) {
        json.writeStartObject();
        json.writeName("comments");
        json.writeStartArray();
        for (CommentData comment : comments) {
            json.writeStartObject();
            person(json, "author", comment.author());
            json.writeStringProperty("content", comment.content());
            time(json, "createdAt", comment.createdAt());
            time(json, "editedAt", comment.editedAt());
            json.writeBooleanProperty("internal", comment.internal());
            json.writeEndObject();
        }
        json.writeEndArray();
        Map<Long, List<NoteData>> notesOf = new LinkedHashMap<>();
        notes.forEach(n -> notesOf.computeIfAbsent(n.taskId(), id -> new ArrayList<>()).add(n));
        json.writeName("tasks");
        json.writeStartArray();
        for (TaskData task : tasks) {
            json.writeStartObject();
            json.writeStringProperty("title", task.title());
            json.writeStringProperty("status", task.status());
            json.writeName("dueDate");
            if (task.dueDate() == null) {
                json.writeNull();
            } else {
                json.writeString(task.dueDate().toString());
            }
            json.writeBooleanProperty("internal", true);
            json.writeName("notes");
            json.writeStartArray();
            for (NoteData note : notesOf.getOrDefault(task.id(), List.of())) {
                json.writeStartObject();
                person(json, "author", note.author());
                json.writeStringProperty("content", note.content());
                time(json, "createdAt", note.createdAt());
                json.writeEndObject();
            }
            json.writeEndArray();
            json.writeEndObject();
        }
        json.writeEndArray();
        json.writeEndObject();
    }

    /**
     * The second part: one file's bytes, read piece by piece. False when the storage has no file for it. A failure to
     * read is {@link Failed} with {@code STORAGE}; a failure to write is an {@link IOException}.
     */
    private boolean addFile(ZipOutputStream zip, File f) throws IOException {
        AttachmentData row = f.row();
        Optional<AttachmentContents> contents;
        try {
            contents = attachments.exportContents(row.id(), row.storage(), row.storageLocation(), row.size());
        } catch (RuntimeException e) {
            throw new Failed(STORAGE, e);
        }
        if (contents.isEmpty()) {
            return false;
        }
        zip.putNextEntry(new ZipEntry(f.path()));
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = contents.get().getInputStream()) {
            while (true) {
                int n;
                try {
                    n = in.read(buffer);
                } catch (IOException | RuntimeException e) {
                    throw new Failed(STORAGE, e);
                }
                if (n < 0) {
                    break;
                }
                zip.write(buffer, 0, n);
            }
        }
        zip.closeEntry();
        return true;
    }

    private static void writeManifest(ZipOutputStream zip, Job job, Counts counts, List<AttachmentData> missing) {
        entry(zip, "manifest.json", json -> {
            json.writeStartObject();
            json.writeStringProperty("code", job.company().code());
            json.writeStringProperty("name", job.company().name());
            time(json, "exportedAt", job.exportedAt());
            json.writeBooleanProperty("includeInternal", job.includeInternal());
            json.writeBooleanProperty("includePrivate", job.includePrivate());
            json.writeNumberProperty("issues", counts.issues);
            json.writeNumberProperty("comments", counts.comments);
            json.writeNumberProperty("attachments", counts.attachments);
            json.writeNumberProperty("customers", counts.customers);
            json.writeName("missingFiles");
            json.writeStartArray();
            for (AttachmentData row : missing) {
                json.writeStartObject();
                json.writeStringProperty("attachmentId", row.id().toString());
                json.writeStringProperty("fileName", row.fileName());
                json.writeEndObject();
            }
            json.writeEndArray();
            json.writeEndObject();
        });
    }

    /** What one JSON entry holds. */
    @FunctionalInterface
    private interface Content {
        void write(JsonGenerator json);
    }

    /** One JSON entry of the ZIP, streamed into it. */
    private static void entry(ZipOutputStream zip, String path, Content content) {
        try {
            zip.putNextEntry(new ZipEntry(path));
            try (JsonGenerator json = JSON.createGenerator(new KeepOpen(zip))) {
                content.write(json);
            }
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A person as the ZIP has them (acceptance.md 5절 20): the portal's {@code Person} without the photo. A customer
     * with the account's ID and name as the row has them now (anonymous values for an anonymized account, 5절 17);
     * staff as the support team, with neither.
     */
    private static void person(JsonGenerator json, String name, Writer writer) {
        json.writeName(name);
        json.writeStartObject();
        if (writer.role() == Role.CUSTOMER) {
            json.writeStringProperty("kind", "CUSTOMER");
            json.writeNumberProperty("accountId", writer.accountId());
            json.writeStringProperty("name", writer.name());
        } else {
            json.writeStringProperty("kind", "SUPPORT_TEAM");
            json.writeName("accountId");
            json.writeNull();
            json.writeName("name");
            json.writeNull();
        }
        json.writeEndObject();
    }

    /** A time in the form of the API (TIME-1): UTC, ISO-8601; null stays null. */
    private static void time(JsonGenerator json, String name, Instant value) {
        json.writeName(name);
        if (value == null) {
            json.writeNull();
        } else {
            json.writeString(TIME.format(value.atOffset(ZoneOffset.UTC)));
        }
    }

    /** The ZIP's stream as a JSON entry writes to it: closing the generator ends the entry, not the ZIP. */
    private static final class KeepOpen extends OutputStream {

        private final OutputStream out;

        KeepOpen(OutputStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            out.flush();
        }
    }
}
