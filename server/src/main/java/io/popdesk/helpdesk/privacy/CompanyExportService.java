package io.popdesk.helpdesk.privacy;

import io.popdesk.ApiError;
import io.popdesk.ApiException;
import io.popdesk.InstanceLock;
import io.popdesk.Transactions;
import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.account.CurrentAccount;
import io.popdesk.helpdesk.audit.AuditAction;
import io.popdesk.helpdesk.audit.AuditLog;
import io.popdesk.helpdesk.privacy.CompanyExportRepository.Company;
import io.popdesk.helpdesk.privacy.CompanyExportRepository.ExportRow;
import io.popdesk.helpdesk.privacy.CompanyExportWriter.Failed;
import io.popdesk.helpdesk.privacy.CompanyExportWriter.Job;
import io.popdesk.notification.AdminInbox;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * The company export (PII-8, S10B-AC-01 to S10B-AC-10, slice 10b design 0절 1 to 6): asking for one, the list, and
 * the file. Admins only; the security filter chain has checked that, and this checks it again.
 *
 * <ul>
 *   <li>One export runs at a time in the whole installation (acceptance.md 5절 3): a flag in the app, which a new
 *       export takes before its row is written and the export gives back once its end is committed, and the unique
 *       index on {@code RUNNING} rows behind it. popdesk runs one instance (D-23).
 *   <li>The ZIP is written in the background ({@link CompanyExportWriter}) on a thread of its own, one export at a
 *       time, which this bean starts and stops with the app ({@link SmartLifecycle}). While the app has lost its
 *       single-instance lock a new export is 503 (acceptance.md 5절 15), and the thread looks once more before it
 *       starts writing.
 *   <li>When the app starts, after the migrations and before the web server takes a request
 *       ({@link #afterPropertiesSet}), the exports of its last run are ended (S10B-AC-06).
 * </ul>
 *
 * The ZIP is {@code {id}.zip} in {@code POPDESK_EXPORT_DIR}, kept for 24 hours after it became {@code DONE}
 * ({@link #expireDue}). Logs name the export's ID and the company's code, never a person or a file name.
 */
@Service
public class CompanyExportService implements InitializingBean, SmartLifecycle {

    /** Where the ZIP files are written (acceptance.md 5절 1, docs/operate/configuration.md). */
    public static final String DIR_KEY = "POPDESK_EXPORT_DIR";

    static final String DEFAULT_DIR = "/tmp/popdesk-exports";

    /** How long a ZIP can be downloaded after it became {@code DONE} (S10B-AC-07, S10B-AC-08). */
    static final Duration KEPT = Duration.ofHours(24);

    /** Seconds a client refused while the app has lost its lock waits before it tries again (S10B-AC-02). */
    static final long RETRY_AFTER = 5;

    static final String RUNNING = "RUNNING";
    static final String DONE = "DONE";
    static final String EXPIRED = "EXPIRED";
    static final String INTERRUPTED = "INTERRUPTED";

    private static final Logger log = LoggerFactory.getLogger(CompanyExportService.class);

    /**
     * The name of a file an export writes: {@code {id}.zip}, or {@code {id}.zip.part} while it is written. Only such
     * files are deleted when the app starts, so a folder shared with other files loses none of them.
     */
    private static final Pattern EXPORT_FILE =
            Pattern.compile("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.zip(\\.part)?");

    /**
     * An export as the API gives it: {@code state} is {@code EXPIRED} for a {@code DONE} export whose ZIP is over 24
     * hours old (S10B-AC-03); {@code requestedByName} is the current name of the account that asked.
     */
    public record Export(UUID id, String state, String failure, boolean includeInternal, boolean includePrivate,
            long requestedById, String requestedByName, Instant requestedAt, Instant finishedAt, Long size) {}

    /** The ZIP of a {@code DONE} export that can be downloaded: the company's code and when it was asked for. */
    public record ZipFile(String companyCode, Instant requestedAt, long size) {}

    /** A download: the ZIP and its bytes, open. The caller closes the stream. */
    public record Download(ZipFile file, InputStream contents) {}

    private final CompanyExportRepository repository;
    private final CompanyExportWriter writer;
    private final AuditLog audit;
    private final AdminInbox inbox;
    private final Transactions transactions;
    private final InstanceLock lock;
    private final Clock clock;
    private final Path folder;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ExecutorService executor;
    /** The company codes of the exports {@link #afterPropertiesSet} interrupted, until {@link #start} tells of them. */
    private volatile List<String> interrupted = List.of();

    CompanyExportService(CompanyExportRepository repository, CompanyExportWriter writer, AuditLog audit,
            AdminInbox inbox, Transactions transactions, InstanceLock lock, Clock clock, Environment environment) {
        this.repository = repository;
        this.writer = writer;
        this.audit = audit;
        this.inbox = inbox;
        this.transactions = transactions;
        this.lock = lock;
        this.clock = clock;
        String configured = environment.getProperty(DIR_KEY);
        this.folder = Path.of(configured == null || configured.isBlank() ? DEFAULT_DIR : configured.strip())
                .toAbsolutePath();
    }

    /**
     * Asks for an export (S10B-AC-01, S10B-AC-02, S10B-AC-09). Checks, in this order: role (403), the company (404),
     * the body (400 {@code REQUIRED} for each choice missing), the instance lock (503), another export running (409
     * {@code EXPORT_RUNNING}). Then the export as {@code RUNNING} and {@code COMPANY_EXPORTED} are written in one
     * transaction, and the ZIP is written in the background.
     */
    public Export create(CurrentAccount admin, String code, Boolean includeInternal, Boolean includePrivate) {
        adminOnly(admin);
        Company company = repository.findCompany(code).orElseThrow(ApiException::notFound);
        List<ApiError.FieldError> missing = new ArrayList<>();
        if (includeInternal == null) {
            missing.add(new ApiError.FieldError("includeInternal", "REQUIRED"));
        }
        if (includePrivate == null) {
            missing.add(new ApiError.FieldError("includePrivate", "REQUIRED"));
        }
        if (!missing.isEmpty()) {
            throw ApiException.invalid(missing);
        }
        if (!lock.held()) {
            throw ApiException.busy(RETRY_AFTER);
        }
        if (!running.compareAndSet(false, true)) {
            throw exportRunning();
        }
        UUID id = UUID.randomUUID();
        Instant now = now();
        Job job = new Job(id, company, includeInternal, includePrivate, now);
        try {
            transactions.run(() -> {
                repository.insert(id, company.id(), admin.id(), includeInternal, includePrivate, now);
                Map<String, Object> target = new LinkedHashMap<>();
                target.put("type", "COMPANY");
                target.put("code", company.code());
                Map<String, Object> changes = new LinkedHashMap<>();
                changes.put("includeInternal", includeInternal);
                changes.put("includePrivate", includePrivate);
                audit.record(admin.actor(), AuditAction.COMPANY_EXPORTED, target, changes);
            });
        } catch (DuplicateKeyException e) {
            running.set(false);
            throw exportRunning();
        } catch (RuntimeException | Error e) {
            running.set(false);
            throw e;
        }
        try {
            ExecutorService thread = executor;
            if (thread == null) {
                throw new RejectedExecutionException("The company export thread is stopped");
            }
            thread.execute(() -> run(job, admin.id()));
        } catch (RejectedExecutionException e) {
            log.warn("Company export {} of {} could not start: the app is stopping.", id, company.code());
            end(job, INTERRUPTED);
            running.set(false);
            throw ApiException.busy(RETRY_AFTER);
        }
        log.info("Company export {} of {} started.", id, company.code());
        return new Export(id, RUNNING, null, includeInternal, includePrivate, admin.id(), admin.name(), now, null,
                null);
    }

    /** The company's last 50 exports, the latest asked for first (S10B-AC-03); 404 for no such company. */
    public List<Export> list(CurrentAccount admin, String code) {
        adminOnly(admin);
        Company company = repository.findCompany(code).orElseThrow(ApiException::notFound);
        Instant now = now();
        return repository.list(company.id()).stream().map(row -> export(row, now)).toList();
    }

    /**
     * The ZIP of a {@code DONE} export that is not over 24 hours old (S10B-AC-07), for a {@code HEAD}: records
     * nothing. 404 for an ID that is not one, no such export, another state, an old one even before the cleanup job
     * deleted its file, and a file that is gone, all with one body.
     */
    public ZipFile check(CurrentAccount admin, String id) {
        adminOnly(admin);
        return find(id).file();
    }

    /**
     * Opens the ZIP for a {@code GET} as {@link #check} finds it, and records {@code COMPANY_EXPORT_DOWNLOADED} by the
     * admin with the export's ID as text (S10B-AC-09). Any admin downloads it, not only the one who asked.
     */
    public Download download(CurrentAccount admin, String id) {
        adminOnly(admin);
        Found found = find(id);
        InputStream contents;
        try {
            contents = Files.newInputStream(CompanyExportWriter.zipOf(folder, found.id()));
        } catch (NoSuchFileException e) {
            throw ApiException.notFound();
        } catch (IOException e) {
            log.error("The ZIP of company export {} could not be opened ({}).", found.id(),
                    e.getClass().getSimpleName());
            throw new IllegalStateException("The ZIP of a company export could not be opened", e);
        }
        try {
            transactions.run(() -> {
                Map<String, Object> target = new LinkedHashMap<>();
                target.put("type", "COMPANY");
                target.put("code", found.file().companyCode());
                Map<String, Object> changes = new LinkedHashMap<>();
                changes.put("exportId", found.id().toString());
                audit.record(admin.actor(), AuditAction.COMPANY_EXPORT_DOWNLOADED, target, changes);
            });
        } catch (RuntimeException | Error e) {
            close(contents, found.id());
            throw e;
        }
        log.info("Company export {} of {} is downloaded.", found.id(), found.file().companyCode());
        return new Download(found.file(), contents);
    }

    /** An export found for its file, by its ID. */
    private record Found(UUID id, ZipFile file) {}

    private Found find(String id) {
        UUID exportId = parse(id).orElseThrow(ApiException::notFound);
        ExportRow row = repository.find(exportId).orElseThrow(ApiException::notFound);
        if (!DONE.equals(export(row, now()).state())) {
            throw ApiException.notFound();
        }
        Path zip = CompanyExportWriter.zipOf(folder, exportId);
        long size;
        try {
            size = Files.size(zip);
        } catch (IOException e) {
            throw ApiException.notFound();
        }
        return new Found(exportId, new ZipFile(row.companyCode(), row.requestedAt(), size));
    }

    /**
     * The cleanup job's work (S10B-AC-08, OPS-2): the ZIP of every export that became {@code DONE} 24 hours ago or
     * more is deleted, and the export is stored as {@code EXPIRED}; its row stays. An export whose file cannot be
     * deleted stays {@code DONE} until a later run, and is not downloaded meanwhile.
     *
     * @return how many exports became {@code EXPIRED}
     */
    long expireDue(Instant now) {
        long expired = 0;
        for (UUID id : repository.listDoneUntil(now.minus(KEPT))) {
            try {
                Files.deleteIfExists(CompanyExportWriter.zipOf(folder, id));
            } catch (IOException e) {
                log.warn("The ZIP of company export {} could not be deleted ({}); it is tried again later.", id,
                        e.getClass().getSimpleName());
                continue;
            }
            if (transactions.run(() -> repository.updateExpired(id))) {
                expired++;
            }
        }
        return expired;
    }

    /**
     * Ends the exports of the last run, once, when the app starts (S10B-AC-06, acceptance.md 5절 15): every export
     * still {@code RUNNING} becomes {@code FAILED} with {@code INTERRUPTED}; of the export files in the export folder
     * ({@link #EXPORT_FILE}) only the ZIP of each {@code DONE} export stays; and a {@code DONE} export whose ZIP is gone becomes {@code EXPIRED}. A
     * folder that is not there yet is made by the first export. The admins are told of each interrupted export in
     * {@link #start}, once the first admin is made (B2-AC-05).
     */
    @Override
    public void afterPropertiesSet() {
        Instant now = now();
        List<String> ended = transactions.run(() -> repository.updateInterrupted(now));
        interrupted = ended;
        Set<UUID> done = new HashSet<>(repository.listDone());
        int deleted = 0;
        if (Files.isDirectory(folder)) {
            Set<String> kept = new HashSet<>();
            done.forEach(id -> kept.add(CompanyExportWriter.zipOf(folder, id).getFileName().toString()));
            try (Stream<Path> files = Files.list(folder)) {
                for (Path file : files.toList()) {
                    String name = file.getFileName().toString();
                    if (EXPORT_FILE.matcher(name).matches() && !kept.contains(name) && Files.isRegularFile(file)) {
                        Files.deleteIfExists(file);
                        deleted++;
                    }
                }
            } catch (IOException e) {
                log.warn("The export folder ({}) could not be cleared of the files of the last run ({}).", DIR_KEY,
                        e.getClass().getSimpleName());
            }
        }
        int gone = 0;
        for (UUID id : done) {
            if (!Files.isRegularFile(CompanyExportWriter.zipOf(folder, id))
                    && transactions.run(() -> repository.updateExpired(id))) {
                gone++;
            }
        }
        if (ended.size() + deleted + gone > 0) {
            log.info("Company exports of the last run: {} interrupted, {} files deleted, {} without a file expired.",
                    ended.size(), deleted, gone);
        }
    }

    /** Writes the ZIP on the export thread and ends the export; gives the one place back at the end. */
    private void run(Job job, long requestedBy) {
        String code = job.company().code();
        try {
            if (!lock.held()) {
                log.warn("Company export {} of {} did not start: the app has lost its single-instance lock.",
                        job.id(), code);
                removeFiles(job.id());
                end(job, INTERRUPTED);
                return;
            }
            long size;
            try {
                size = writer.write(job, folder);
            } catch (Failed e) {
                log.warn("Company export {} of {} failed ({}, {}).", job.id(), code, e.failure(),
                        e.getCause() == null ? "-" : e.getCause().getClass().getSimpleName());
                removeFiles(job.id());
                end(job, e.failure());
                return;
            }
            boolean done = transactions.run(() -> {
                if (!repository.updateDone(job.id(), size, now())) {
                    return false;
                }
                inbox.companyExportReady(code, requestedBy);
                return true;
            });
            if (done) {
                log.info("Company export {} of {} is done: {} bytes.", job.id(), code, size);
            } else {
                removeFiles(job.id());
            }
        } catch (RuntimeException e) {
            log.error("Company export {} of {} failed ({}).", job.id(), code, e.getClass().getSimpleName());
            removeFiles(job.id());
            end(job, CompanyExportWriter.UNEXPECTED);
        } finally {
            running.set(false);
        }
    }

    /** A {@code RUNNING} export becomes {@code FAILED} and every admin is told (S10B-AC-05). */
    private void end(Job job, String failure) {
        try {
            transactions.run(() -> {
                if (repository.updateFailed(job.id(), failure, now())) {
                    inbox.companyExportFailed(job.company().code());
                }
            });
        } catch (RuntimeException e) {
            log.error("Company export {} could not be stored as FAILED ({}); the next start does it.", job.id(),
                    e.getClass().getSimpleName());
        }
    }

    private void removeFiles(UUID id) {
        for (Path file : List.of(CompanyExportWriter.partOf(folder, id), CompanyExportWriter.zipOf(folder, id))) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.warn("A file of company export {} could not be deleted ({}); the next start does it.", id,
                        e.getClass().getSimpleName());
            }
        }
    }

    private static void close(InputStream contents, UUID id) {
        try {
            contents.close();
        } catch (IOException e) {
            log.debug("Closing the ZIP of company export {} failed ({})", id, e.getClass().getSimpleName());
        }
    }

    private static Export export(ExportRow row, Instant now) {
        String state = DONE.equals(row.state()) && row.finishedAt() != null
                && !now.isBefore(row.finishedAt().plus(KEPT)) ? EXPIRED : row.state();
        return new Export(row.id(), state, row.failure(), row.includeInternal(), row.includePrivate(),
                row.requestedById(), row.requestedByName(), row.requestedAt(), row.finishedAt(), row.sizeBytes());
    }

    private static Optional<UUID> parse(String id) {
        if (id == null || id.length() != 36) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static ApiException exportRunning() {
        return ApiException.conflict("EXPORT_RUNNING", "Another company export is running.");
    }

    /** The security configuration lets only admins reach the exports; this holds should a path slip out of it. */
    private static void adminOnly(CurrentAccount account) {
        if (account.role() != Role.ADMIN) {
            throw ApiException.forbidden();
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    // The export thread

    /**
     * Tells every active admin of each export the start interrupted, with {@code COMPANY_EXPORT_FAILED} (B2-AC-05),
     * then starts the export thread. This runs after the first admin is made ({@code FirstAdminSetup}), which the
     * end of the last run comes before.
     */
    @Override
    public void start() {
        List<String> codes = interrupted;
        interrupted = List.of();
        if (!codes.isEmpty()) {
            try {
                transactions.run(() -> codes.forEach(inbox::companyExportFailed));
            } catch (RuntimeException e) {
                log.error("The admins could not be told of {} company exports the start interrupted ({}).",
                        codes.size(), e.getClass().getSimpleName());
            }
        }
        executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("popdesk-company-export")
                .factory());
    }

    @Override
    public void stop() {
        ExecutorService thread = executor;
        executor = null;
        if (thread != null) {
            thread.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }
}
